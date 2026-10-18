//! A vault is one git repository holding projects and their logs.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, Once};

use chrono::{DateTime, FixedOffset, Local};
use git2::{build::CheckoutBuilder, Repository, Signature};
use serde::{Deserialize, Serialize};

use crate::error::{invalid, CoreError, Result};
use crate::format::{self, Block, FrontMatter, ASSETS_DIR, CONFIG_FILE, LOCAL_DIR, PROJECT_FILE};
use crate::images;
use crate::log::{self, LogEntry, PhotoRef};
use crate::logsession::{self, OpenLogInfo};
use crate::s3::{S3Client, S3Config, S3Credentials};

const LOG_TRAILER: &str = "FieldLog-Log: ";
const EDIT_TRAILER: &str = "FieldLog-Edit: ";
const REVERT_TRAILER: &str = "FieldLog-Revert: ";
/// Marker in the file name of the local side of a sync conflict.
const CONFLICT_MARK: &str = ".conflict-";
const PENDING_UPLOADS: &str = "pending-uploads.txt";

// ---------------------------------------------------------------- records

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum ImageStorage {
    /// Images are committed to the repo under `assets/`. Meant for
    /// self-hosted remotes; hosted ones (GitHub…) fill up quickly.
    Git,
    /// Images live in S3 (or R2); `assets/` is a local cache and is
    /// git-ignored. Uploaded before every push.
    S3,
    /// Images stay on this device in `assets/` (git-ignored) and are queued
    /// for upload, so switching to S3 later syncs them with no rewrite.
    /// The default for new vaults.
    Local,
}

impl ImageStorage {
    /// `assets/` is git-ignored.
    fn ignores_assets(self) -> bool {
        matches!(self, ImageStorage::S3 | ImageStorage::Local)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultConfig {
    pub name: String,
    #[serde(default = "default_storage")]
    pub image_storage: ImageStorage,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub s3: Option<S3Config>,
}

fn default_storage() -> ImageStorage {
    ImageStorage::Git
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Author {
    pub name: String,
    pub email: String,
}

/// Secrets for the git remote. Held by the app (Keystore), passed per call.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct GitCredentials {
    /// OpenSSH or PEM private key text.
    pub ssh_private_key: Option<String>,
    pub ssh_passphrase: Option<String>,
    pub https_username: Option<String>,
    /// Password or personal access token.
    pub https_password: Option<String>,
    /// Pinned SSH host key (hex sha256). `None` = trust on first use; the key
    /// seen is returned in `SyncReport.host_key_sha256` for the app to pin.
    pub known_host_sha256: Option<String>,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct SyncReport {
    pub pulled_commits: u32,
    pub merged: bool,
    pub pushed: bool,
    pub uploaded_assets: u32,
    pub host_key_sha256: Option<String>,
    /// Local versions of logs edited on two devices, kept next to the
    /// remote version.
    pub conflict_copies: Vec<String>,
}

/// Photos that exist only on this device.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PhotoBackup {
    pub count: u32,
    pub bytes: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProjectInfo {
    pub slug: String,
    pub title: String,
    pub entry_count: u32,
    /// RFC 3339; `None` when the project has no entries yet.
    pub last_entry: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct EntrySummary {
    pub id: String,
    pub project: String,
    /// Path relative to the vault root.
    pub path: String,
    /// When the log started (RFC 3339).
    pub created: String,
    pub ended: Option<String>,
    pub tags: Vec<String>,
    pub summary: String,
    pub photo_count: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Entry {
    pub id: String,
    pub project: String,
    pub path: String,
    pub created: String,
    pub tags: Vec<String>,
    pub source: Option<String>,
    pub photos: Vec<String>,
    pub body: String,
}

/// One log, for the blog-style project feed.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LogSummary {
    pub id: String,
    pub project: String,
    pub path: String,
    pub started: String,
    pub ended: Option<String>,
    pub tags: Vec<String>,
    /// First lines of the notes.
    pub summary: String,
    /// Up to two photos for thumbnails.
    pub preview_photos: Vec<PhotoRef>,
    pub photo_count: u32,
    pub entry_count: u32,
    /// The local side of a sync conflict, kept next to the original.
    pub conflict: bool,
}

/// A whole log, for the log view.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LogView {
    pub id: String,
    pub project: String,
    pub path: String,
    pub started: String,
    pub ended: Option<String>,
    pub tags: Vec<String>,
    pub source: Option<String>,
    pub entries: Vec<LogEntry>,
    pub conflict: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CommitInfo {
    pub commit: String,
    pub entry: EntrySummary,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchHit {
    pub entry: EntrySummary,
    pub snippet: String,
    pub score: u32,
}

// ---------------------------------------------------------------- vault

#[derive(uniffi::Object)]
pub struct Vault {
    root: PathBuf,
    repo: Mutex<Repository>,
    config: Mutex<VaultConfig>,
    author: Mutex<Author>,
    /// Names the local copy when a sync conflict keeps both versions.
    device: Mutex<String>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

static PLATFORM_INIT: Once = Once::new();

/// Point libgit2's OpenSSL at Android's system CA store for HTTPS remotes.
fn platform_init() {
    PLATFORM_INIT.call_once(|| {
        #[cfg(target_os = "android")]
        {
            // SAFETY: called once, before any other libgit2 use from this crate.
            let _ = unsafe { git2::opts::set_ssl_cert_dir("/system/etc/security/cacerts") };
        }
    });
}

fn random_suffix() -> String {
    let mut b = [0u8; 2];
    getrandom::fill(&mut b).expect("os randomness");
    hex::encode(b)
}

fn write_gitignore(root: &Path, storage: ImageStorage) -> Result<()> {
    let mut s = format!("{LOCAL_DIR}/\n");
    if storage.ignores_assets() {
        s.push_str(&format!("{ASSETS_DIR}/\n"));
    }
    fs::write(root.join(".gitignore"), s)?;
    Ok(())
}

impl Vault {
    fn repo(&self) -> MutexGuard<'_, Repository> {
        lock(&self.repo)
    }

    fn signature(&self) -> Result<Signature<'static>> {
        let a = lock(&self.author);
        Ok(Signature::now(&a.name, &a.email)?)
    }

    fn local_dir(&self) -> Result<PathBuf> {
        let d = self.root.join(LOCAL_DIR);
        fs::create_dir_all(&d)?;
        Ok(d)
    }

    pub(crate) fn root_path(&self) -> &Path {
        &self.root
    }

    /// Stage `add` (files written to the workdir) and `remove`, commit on HEAD.
    fn commit_paths(
        &self,
        repo: &Repository,
        add: &[String],
        remove: &[String],
        message: &str,
    ) -> Result<git2::Oid> {
        let mut index = repo.index()?;
        for p in add {
            index.add_path(Path::new(p))?;
        }
        for p in remove {
            index.remove_path(Path::new(p))?;
        }
        index.write()?;
        let tree = repo.find_tree(index.write_tree()?)?;
        let sig = self.signature()?;
        let parent = match repo.head() {
            Ok(h) => Some(h.peel_to_commit()?),
            Err(_) => None,
        };
        let parents: Vec<&git2::Commit> = parent.iter().collect();
        Ok(repo.commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)?)
    }

    fn open_at(root: PathBuf, repo: Repository, author: Author) -> Result<Self> {
        let cfg_path = root.join(CONFIG_FILE);
        let config: VaultConfig = if cfg_path.exists() {
            toml::from_str(&fs::read_to_string(&cfg_path)?)?
        } else {
            return Err(CoreError::NotFound {
                msg: format!("{CONFIG_FILE} not found; not a FieldLog vault"),
            });
        };
        Ok(Vault {
            root,
            repo: Mutex::new(repo),
            config: Mutex::new(config),
            author: Mutex::new(author),
            device: Mutex::new("device".into()),
        })
    }

    fn bootstrap(root: &Path, repo: &Repository, name: &str, author: &Author) -> Result<()> {
        let cfg = VaultConfig {
            name: name.to_string(),
            image_storage: ImageStorage::Local,
            s3: None,
        };
        fs::write(root.join(CONFIG_FILE), toml::to_string(&cfg)?)?;
        write_gitignore(root, cfg.image_storage)?;
        let mut index = repo.index()?;
        index.add_path(Path::new(CONFIG_FILE))?;
        index.add_path(Path::new(".gitignore"))?;
        index.write()?;
        let tree = repo.find_tree(index.write_tree()?)?;
        let sig = Signature::now(&author.name, &author.email)?;
        let parent = match repo.head() {
            Ok(h) => Some(h.peel_to_commit()?),
            Err(_) => None,
        };
        let parents: Vec<&git2::Commit> = parent.iter().collect();
        repo.commit(
            Some("HEAD"),
            &sig,
            &sig,
            &format!("Create FieldLog vault \"{name}\""),
            &tree,
            &parents,
        )?;
        Ok(())
    }

    fn entry_files(&self, project: Option<&str>) -> Vec<PathBuf> {
        let start = match project {
            Some(p) => self.root.join(p),
            None => self.root.clone(),
        };
        let root = self.root.clone();
        walkdir::WalkDir::new(&start)
            .into_iter()
            .filter_entry(move |e| {
                let name = e.file_name().to_string_lossy();
                if e.depth() == 0 {
                    return true;
                }
                if name.starts_with('.') {
                    return false;
                }
                // Skip the vault-level assets dir.
                !(e.file_type().is_dir() && e.path() == root.join(ASSETS_DIR))
            })
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.file_type().is_file()
                    && e.path().extension().is_some_and(|x| x == "md")
                    && e.file_name() != PROJECT_FILE
            })
            .map(|e| e.into_path())
            .collect()
    }

    fn load(&self, path: &Path) -> Option<(FrontMatter, String, String)> {
        let text = fs::read_to_string(path).ok()?;
        let (fm, body) = format::parse(&text).ok()?;
        let rel = path
            .strip_prefix(&self.root)
            .ok()?
            .to_string_lossy()
            .replace('\\', "/");
        Some((fm, body, rel))
    }

    // ------------------------------------------------------------ logs

    /// Unused path and id for a new log started at `started`.
    pub(crate) fn new_log_path(
        &self,
        project: &str,
        started: &DateTime<FixedOffset>,
    ) -> (String, String) {
        loop {
            let s = random_suffix();
            let r = format::entry_rel_path(project, started, &s);
            if !self.root.join(&r).exists() {
                return (format::entry_id(started, &s), r);
            }
        }
    }

    fn check_rel(&self, rel: &str) -> Result<PathBuf> {
        let abs = self.root.join(rel);
        if rel.contains("..") || rel.starts_with('/') || !abs.starts_with(&self.root) {
            return Err(invalid("path outside vault"));
        }
        Ok(abs)
    }

    pub(crate) fn load_log(&self, rel: &str) -> Result<(FrontMatter, Vec<LogEntry>)> {
        let abs = self.check_rel(rel)?;
        let text = fs::read_to_string(&abs)?;
        let (fm, body) = format::parse(&text)?;
        let entries = log::parse_body(&fm, &body);
        Ok((fm, entries))
    }

    /// Write a log file and commit it (with its photos in git storage).
    /// Returns `None` for an edit that left the file as committed.
    pub(crate) fn commit_log_file(
        &self,
        rel: &str,
        fm: &FrontMatter,
        entries: &[LogEntry],
        editing: bool,
    ) -> Result<Option<CommitInfo>> {
        let abs = self.check_rel(rel)?;
        let body = log::render_body(fm, entries);
        let text = format::render(fm, &body)?;
        fs::create_dir_all(abs.parent().unwrap())?;
        fs::write(&abs, &text)?;
        let repo = self.repo();
        if editing && head_blob(&repo, rel).as_deref() == Some(text.as_bytes()) {
            return Ok(None);
        }
        let mut add = vec![rel.to_string()];
        if self.config().image_storage == ImageStorage::Git {
            for p in log::photos(entries) {
                let a = format::asset_rel_path(&p.sha256, &p.ext);
                if !self.root.join(&a).exists() {
                    return Err(CoreError::NotFound {
                        msg: format!("photo {a}"),
                    });
                }
                add.push(a);
            }
        }
        let (verb, trailer) = if editing {
            ("edit ", EDIT_TRAILER)
        } else {
            ("", LOG_TRAILER)
        };
        let message = format!(
            "{}: {verb}{}\n\n{trailer}{}\n",
            fm.project,
            log_title(entries),
            fm.id
        );
        let oid = self.commit_paths(&repo, &add, &[], &message)?;
        Ok(Some(CommitInfo {
            commit: oid.to_string(),
            entry: summarize(fm, &body, rel.to_string()),
        }))
    }

    /// Commit a finished log in one go (share sheet).
    pub(crate) fn commit_new_log(
        &self,
        project: &str,
        entries: Vec<LogEntry>,
        tags: Vec<String>,
        source: &str,
    ) -> Result<CommitInfo> {
        if !self.root.join(project).is_dir() {
            return Err(CoreError::NotFound {
                msg: format!("project {project}"),
            });
        }
        let now = crate::log::now_secs();
        let started = entries
            .first()
            .and_then(|e| log::parse_time(&e.time))
            .unwrap_or(now);
        let (id, rel) = self.new_log_path(project, &started);
        let mut tags: Vec<String> = tags
            .iter()
            .map(|t| format::slugify(t))
            .filter(|t| !t.is_empty())
            .collect();
        tags.dedup();
        let fm = FrontMatter {
            id,
            project: project.to_string(),
            started,
            ended: Some(now),
            tags,
            source: Some(source.to_string()),
            photos: log::photos(&entries)
                .into_iter()
                .map(|p| p.sha256)
                .collect(),
            extra: Default::default(),
        };
        match self.commit_log_file(&rel, &fm, &entries, false) {
            Ok(Some(info)) => Ok(info),
            Ok(None) => Err(invalid("nothing to commit")),
            Err(e) => {
                let _ = fs::remove_file(self.root.join(&rel));
                Err(e)
            }
        }
    }

    /// Delete a committed log in a new commit. History keeps it.
    pub(crate) fn remove_log_file(&self, rel: &str, fm: &FrontMatter) -> Result<CommitInfo> {
        let abs = self.check_rel(rel)?;
        let repo = self.repo();
        let entries = head_blob(&repo, rel)
            .and_then(|b| String::from_utf8(b).ok())
            .and_then(|t| format::parse(&t).ok())
            .map(|(f, body)| log::parse_body(&f, &body))
            .unwrap_or_default();
        let _ = fs::remove_file(&abs);
        let message = format!(
            "{}: remove {}\n\n{REVERT_TRAILER}{}\n",
            fm.project,
            log_title(&entries),
            fm.id
        );
        let oid = self.commit_paths(&repo, &[], &[rel.to_string()], &message)?;
        let body = log::render_body(fm, &entries);
        Ok(CommitInfo {
            commit: oid.to_string(),
            entry: summarize(fm, &body, rel.to_string()),
        })
    }

    /// Put a file back to its committed version.
    pub(crate) fn restore_file(&self, rel: &str) -> Result<()> {
        self.check_rel(rel)?;
        let repo = self.repo();
        repo.checkout_head(Some(CheckoutBuilder::new().force().path(rel)))?;
        Ok(())
    }

    /// Resolve merge conflicts on log files by keeping both: theirs at the
    /// original path, ours next to it as `<name>.conflict-<device>-<time>.md`.
    /// Conflicts on anything else are an error.
    fn keep_both(&self, idx: &mut git2::Index) -> Result<Vec<String>> {
        let conflicts: Vec<git2::IndexConflict> = idx
            .conflicts()?
            .collect::<std::result::Result<Vec<_>, git2::Error>>()?;
        let device = {
            let d = format::slugify(&lock(&self.device));
            if d.is_empty() {
                "device".to_string()
            } else {
                d
            }
        };
        let stamp = Local::now().format("%Y%m%dT%H%M%S").to_string();
        let mut copies = Vec::new();
        let mut other = Vec::new();
        for c in conflicts {
            let path = c
                .our
                .as_ref()
                .or(c.their.as_ref())
                .or(c.ancestor.as_ref())
                .map(|e| String::from_utf8_lossy(&e.path).into_owned())
                .unwrap_or_default();
            let is_log = path.ends_with(".md")
                && path.contains('/')
                && !path.ends_with(&format!("/{PROJECT_FILE}"));
            if !is_log {
                other.push(path);
                continue;
            }
            idx.conflict_remove(Path::new(&path))?;
            match (c.our, c.their) {
                (Some(ours), Some(theirs)) => {
                    idx.add(&stage0(theirs))?;
                    let stem = path.trim_end_matches(".md");
                    let copy = format!("{stem}{CONFLICT_MARK}{device}-{stamp}.md");
                    let mut e = stage0(ours);
                    e.flags = copy.len().min(0xFFF) as u16;
                    e.path = copy.clone().into_bytes();
                    idx.add(&e)?;
                    copies.push(copy);
                }
                (Some(e), None) | (None, Some(e)) => idx.add(&stage0(e))?,
                (None, None) => {}
            }
        }
        if !other.is_empty() {
            return Err(CoreError::Conflict {
                msg: other.join(", "),
            });
        }
        Ok(copies)
    }

    fn asset_rel_paths(&self) -> Vec<String> {
        let dir = self.root.join(ASSETS_DIR);
        let mut out: Vec<String> = walkdir::WalkDir::new(&dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .filter(|e| e.path().extension().is_none_or(|x| x != "tmp"))
            .filter_map(|e| {
                e.path()
                    .strip_prefix(&self.root)
                    .ok()
                    .map(|p| p.to_string_lossy().replace('\\', "/"))
            })
            .collect();
        out.sort();
        out
    }

    fn pending_path(&self) -> Result<PathBuf> {
        Ok(self.local_dir()?.join(PENDING_UPLOADS))
    }

    fn pending_uploads(&self) -> Vec<String> {
        self.pending_path()
            .ok()
            .and_then(|p| fs::read_to_string(p).ok())
            .map(|s| {
                s.lines()
                    .map(str::to_string)
                    .filter(|l| !l.is_empty())
                    .collect()
            })
            .unwrap_or_default()
    }

    fn set_pending_uploads(&self, items: &[String]) -> Result<()> {
        let mut s = items.join("\n");
        if !s.is_empty() {
            s.push('\n');
        }
        fs::write(self.pending_path()?, s)?;
        Ok(())
    }
}

fn head_blob(repo: &Repository, rel: &str) -> Option<Vec<u8>> {
    let tree = repo.head().ok()?.peel_to_tree().ok()?;
    let entry = tree.get_path(Path::new(rel)).ok()?;
    let blob = repo.find_blob(entry.id()).ok()?;
    Some(blob.content().to_vec())
}

/// The same index entry as a normal (stage 0) entry.
fn stage0(mut e: git2::IndexEntry) -> git2::IndexEntry {
    e.flags &= !0x3000;
    e
}

fn summarize(fm: &FrontMatter, body: &str, rel: String) -> EntrySummary {
    let entries = log::parse_body(fm, body);
    EntrySummary {
        id: fm.id.clone(),
        project: fm.project.clone(),
        path: rel,
        created: fm.started.to_rfc3339(),
        ended: fm.ended.map(|d| d.to_rfc3339()),
        tags: fm.tags.clone(),
        summary: log::summary(&entries, 120),
        photo_count: log::photos(&entries).len() as u32,
    }
}

fn log_summary(fm: &FrontMatter, body: &str, rel: String) -> LogSummary {
    let entries = log::parse_body(fm, body);
    let photos = log::photos(&entries);
    LogSummary {
        id: fm.id.clone(),
        project: fm.project.clone(),
        conflict: rel.contains(CONFLICT_MARK),
        path: rel,
        started: fm.started.to_rfc3339(),
        ended: fm.ended.map(|d| d.to_rfc3339()),
        tags: fm.tags.clone(),
        summary: log::summary(&entries, 240),
        photo_count: photos.len() as u32,
        preview_photos: photos.into_iter().take(2).collect(),
        entry_count: entries.len() as u32,
    }
}

/// First line of a commit subject for a log.
fn log_title(entries: &[LogEntry]) -> String {
    let s = log::summary(entries, 60);
    if s.is_empty() {
        let n = log::photos(entries).len();
        if n == 1 {
            "1 photo".into()
        } else {
            format!("{n} photos")
        }
    } else {
        s
    }
}

#[uniffi::export]
impl Vault {
    /// Create a new vault (git repo) at `path`.
    #[uniffi::constructor]
    pub fn create(path: String, name: String, author: Author) -> Result<Arc<Self>> {
        platform_init();
        let root = PathBuf::from(&path);
        fs::create_dir_all(&root)?;
        if root.join(CONFIG_FILE).exists() {
            return Err(invalid("a vault already exists here"));
        }
        let mut opts = git2::RepositoryInitOptions::new();
        opts.initial_head("main");
        let repo = Repository::init_opts(&root, &opts)?;
        Vault::bootstrap(&root, &repo, &name, &author)?;
        Ok(Arc::new(Vault::open_at(root, repo, author)?))
    }

    /// Open an existing vault.
    #[uniffi::constructor]
    pub fn open(path: String, author: Author) -> Result<Arc<Self>> {
        platform_init();
        let root = PathBuf::from(&path);
        let repo = Repository::open(&root)?;
        Ok(Arc::new(Vault::open_at(root, repo, author)?))
    }

    /// Clone a remote into `path`. An empty remote (or one without a
    /// `fieldlog.toml`) is initialised as a vault named `name`.
    #[uniffi::constructor]
    pub fn clone_remote(
        url: String,
        path: String,
        name: String,
        credentials: GitCredentials,
        author: Author,
    ) -> Result<Arc<Self>> {
        platform_init();
        let root = PathBuf::from(&path);
        let seen = Arc::new(Mutex::new(None));
        let mut fo = git2::FetchOptions::new();
        fo.remote_callbacks(remote_callbacks(credentials, seen));
        let repo = git2::build::RepoBuilder::new()
            .fetch_options(fo)
            .clone(&url, &root)?;
        if repo.head().is_err() {
            // Remote HEAD points at a branch that doesn't exist (or the remote
            // is empty). Prefer main, then master, then any branch.
            let mut names: Vec<String> = repo
                .branches(Some(git2::BranchType::Remote))?
                .filter_map(|b| b.ok())
                .filter_map(|(b, _)| b.name().ok().flatten().map(str::to_string))
                .filter(|n| n != "origin/HEAD")
                .collect();
            names.sort_by_key(|n| match n.as_str() {
                "origin/main" => 0,
                "origin/master" => 1,
                _ => 2,
            });
            match names.first() {
                Some(remote_branch) => {
                    let local = remote_branch.trim_start_matches("origin/").to_string();
                    let target = repo
                        .find_branch(remote_branch, git2::BranchType::Remote)?
                        .get()
                        .peel_to_commit()?;
                    let mut b = repo.branch(&local, &target, false)?;
                    b.set_upstream(Some(remote_branch))?;
                    repo.set_head(&format!("refs/heads/{local}"))?;
                    repo.checkout_head(Some(CheckoutBuilder::new().force()))?;
                }
                None => repo.set_head("refs/heads/main")?,
            }
        }
        if !root.join(CONFIG_FILE).exists() {
            Vault::bootstrap(&root, &repo, &name, &author)?;
        }
        Ok(Arc::new(Vault::open_at(root, repo, author)?))
    }

    pub fn path(&self) -> String {
        self.root.to_string_lossy().into_owned()
    }

    pub fn config(&self) -> VaultConfig {
        lock(&self.config).clone()
    }

    pub fn set_author(&self, author: Author) {
        *lock(&self.author) = author;
    }

    /// Short device name used for conflict copies (e.g. the phone model).
    pub fn set_device_name(&self, name: String) {
        *lock(&self.device) = name;
    }

    /// The log currently open in this vault, if any.
    pub fn open_log(&self) -> Option<OpenLogInfo> {
        logsession::open_log_info(self)
    }

    /// Logs, newest first. `limit` 0 = all.
    pub fn list_logs(&self, project: Option<String>, limit: u32) -> Vec<LogSummary> {
        let mut v: Vec<LogSummary> = self
            .entry_files(project.as_deref())
            .iter()
            .filter_map(|p| self.load(p))
            .map(|(fm, body, rel)| log_summary(&fm, &body, rel))
            .collect();
        v.sort_by(|a, b| b.started.cmp(&a.started).then(a.path.cmp(&b.path)));
        if limit > 0 {
            v.truncate(limit as usize);
        }
        v
    }

    pub fn read_log(&self, path: String) -> Result<LogView> {
        let (fm, entries) = self.load_log(&path)?;
        Ok(LogView {
            id: fm.id,
            project: fm.project,
            conflict: path.contains(CONFLICT_MARK),
            path,
            started: fm.started.to_rfc3339(),
            ended: fm.ended.map(|d| d.to_rfc3339()),
            tags: fm.tags,
            source: fm.source,
            entries,
        })
    }

    /// Remove a committed log with a new commit (history keeps it).
    pub fn revert_log(&self, path: String) -> Result<CommitInfo> {
        if self.open_log().is_some_and(|o| o.path == path) {
            return Err(CoreError::LogOpen {
                msg: "this log is open; end or discard it first".into(),
            });
        }
        let (fm, _) = self.load_log(&path)?;
        self.remove_log_file(&path, &fm)
    }

    /// Update and commit `fieldlog.toml` (and `.gitignore` for storage mode).
    ///
    /// - Git → local or S3: assets stop being tracked (they stay on disk)
    ///   and are queued for upload. History keeps what was committed.
    /// - Local → S3: the queue drains on the next sync.
    /// - Local or S3 → git: photos on this device are committed.
    pub fn set_config(&self, config: VaultConfig) -> Result<()> {
        if config.image_storage == ImageStorage::S3 && config.s3.is_none() {
            return Err(invalid("S3 image storage needs an [s3] section"));
        }
        let before = self.config().image_storage;
        let repo = self.repo();
        fs::write(self.root.join(CONFIG_FILE), toml::to_string(&config)?)?;
        write_gitignore(&self.root, config.image_storage)?;
        let mut remove = vec![];
        let mut add = vec![CONFIG_FILE.to_string(), ".gitignore".to_string()];
        let to_ignored = config.image_storage.ignores_assets();
        if to_ignored && !before.ignores_assets() {
            let index = repo.index()?;
            let tracked: Vec<String> = index
                .iter()
                .map(|e| String::from_utf8_lossy(&e.path).into_owned())
                .filter(|p| p.starts_with(&format!("{ASSETS_DIR}/")))
                .collect();
            let mut pending = self.pending_uploads();
            for p in &tracked {
                if let Some(name) = Path::new(p).file_name() {
                    let n = name.to_string_lossy().into_owned();
                    if !pending.contains(&n) {
                        pending.push(n);
                    }
                }
            }
            self.set_pending_uploads(&pending)?;
            remove = tracked;
        } else if !to_ignored && before.ignores_assets() {
            add.extend(self.asset_rel_paths());
            self.set_pending_uploads(&[])?;
        }
        self.commit_paths(&repo, &add, &remove, "Update vault settings")?;
        *lock(&self.config) = config;
        Ok(())
    }

    /// Create a project directory with a README. Returns the slug.
    pub fn create_project(&self, title: String) -> Result<String> {
        let slug = format::slugify(&title);
        if slug.is_empty() || slug == ASSETS_DIR {
            return Err(invalid(format!("unusable project name: {title:?}")));
        }
        let dir = self.root.join(&slug);
        if dir.join(PROJECT_FILE).exists() {
            return Ok(slug);
        }
        fs::create_dir_all(&dir)?;
        fs::write(dir.join(PROJECT_FILE), format!("# {}\n", title.trim()))?;
        let repo = self.repo();
        self.commit_paths(
            &repo,
            &[format!("{slug}/{PROJECT_FILE}")],
            &[],
            &format!("New project: {}", title.trim()),
        )?;
        Ok(slug)
    }

    pub fn projects(&self) -> Result<Vec<ProjectInfo>> {
        let mut out = Vec::new();
        for e in fs::read_dir(&self.root)? {
            let e = e?;
            let name = e.file_name().to_string_lossy().into_owned();
            if !e.file_type()?.is_dir() || name.starts_with('.') || name == ASSETS_DIR {
                continue;
            }
            let readme = e.path().join(PROJECT_FILE);
            let title = fs::read_to_string(&readme)
                .ok()
                .and_then(|s| {
                    s.lines()
                        .find_map(|l| l.strip_prefix("# ").map(|t| t.trim().to_string()))
                })
                .unwrap_or_else(|| name.clone());
            let mut created: Vec<DateTime<FixedOffset>> = self
                .entry_files(Some(&name))
                .iter()
                .filter(|p| !p.to_string_lossy().contains(CONFLICT_MARK))
                .filter_map(|p| self.load(p).map(|(fm, _, _)| fm.started))
                .collect();
            if created.is_empty() && !readme.exists() {
                continue; // a random directory, not a project
            }
            created.sort();
            out.push(ProjectInfo {
                slug: name,
                title,
                entry_count: created.len() as u32,
                last_entry: created.last().map(|d| d.to_rfc3339()),
            });
        }
        out.sort_by(|a, b| b.last_entry.cmp(&a.last_entry).then(a.slug.cmp(&b.slug)));
        Ok(out)
    }

    /// Store image bytes content-addressed under `assets/` and return the
    /// block to place in a draft. Committed with the entry (git storage) or
    /// queued for upload (S3 storage).
    pub fn add_photo(&self, bytes: Vec<u8>) -> Result<Block> {
        if bytes.is_empty() {
            return Err(invalid("empty image"));
        }
        let sha = images::sha256_hex(&bytes);
        let ext = images::sniff_ext(&bytes).to_string();
        let rel = format::asset_rel_path(&sha, &ext);
        let abs = self.root.join(&rel);
        if !abs.exists() {
            fs::create_dir_all(abs.parent().unwrap())?;
            let tmp = abs.with_extension("tmp");
            fs::write(&tmp, &bytes)?;
            fs::rename(&tmp, &abs)?;
        }
        if self.config().image_storage.ignores_assets() {
            let name = format!("{sha}.{ext}");
            let mut pending = self.pending_uploads();
            if !pending.contains(&name) {
                pending.push(name);
                self.set_pending_uploads(&pending)?;
            }
        }
        Ok(Block::photo(sha, ext))
    }

    /// Entries, newest first.
    pub fn list_entries(&self, project: Option<String>, limit: u32) -> Vec<EntrySummary> {
        let mut v: Vec<EntrySummary> = self
            .entry_files(project.as_deref())
            .iter()
            .filter_map(|p| self.load(p))
            .map(|(fm, body, rel)| summarize(&fm, &body, rel))
            .collect();
        v.sort_by(|a, b| b.created.cmp(&a.created));
        if limit > 0 {
            v.truncate(limit as usize);
        }
        v
    }

    pub fn read_entry(&self, path: String) -> Result<Entry> {
        let abs = self.root.join(&path);
        if !abs.starts_with(&self.root) || path.contains("..") {
            return Err(invalid("path outside vault"));
        }
        let text = fs::read_to_string(&abs)?;
        let (fm, body) = format::parse(&text)?;
        Ok(Entry {
            id: fm.id,
            project: fm.project,
            path,
            created: fm.started.to_rfc3339(),
            tags: fm.tags,
            source: fm.source,
            photos: fm.photos,
            body,
        })
    }

    /// Case-insensitive full-text search. Every term must match the body,
    /// tags or project. Optional project / tag filters.
    pub fn search(
        &self,
        query: String,
        project: Option<String>,
        tag: Option<String>,
        limit: u32,
    ) -> Vec<SearchHit> {
        crate::search::search(self, &query, project.as_deref(), tag.as_deref(), limit)
    }

    /// Absolute local path of an image (may not exist yet in S3 mode).
    pub fn photo_path(&self, sha256: String, ext: String) -> String {
        self.root
            .join(format::asset_rel_path(&sha256, &ext))
            .to_string_lossy()
            .into_owned()
    }

    /// Make sure an image is available locally, downloading it from S3 if
    /// needed. Returns the absolute path.
    pub fn fetch_photo(
        &self,
        sha256: String,
        ext: String,
        s3_credentials: Option<S3Credentials>,
    ) -> Result<String> {
        let rel = format::asset_rel_path(&sha256, &ext);
        let abs = self.root.join(&rel);
        if abs.exists() {
            return Ok(abs.to_string_lossy().into_owned());
        }
        let cfg = self.config();
        let (Some(s3), Some(creds)) = (cfg.s3, s3_credentials) else {
            return Err(CoreError::NotFound { msg: rel });
        };
        let bytes = S3Client::new(s3, creds)
            .get(&rel)?
            .ok_or_else(|| CoreError::NotFound { msg: rel.clone() })?;
        if images::sha256_hex(&bytes) != sha256 {
            return Err(CoreError::S3 {
                msg: format!("{rel}: content hash mismatch"),
            });
        }
        fs::create_dir_all(abs.parent().unwrap())?;
        fs::write(&abs, bytes)?;
        Ok(abs.to_string_lossy().into_owned())
    }

    pub fn pending_upload_count(&self) -> u32 {
        self.pending_uploads().len() as u32
    }

    /// Photos not yet in S3 (local and S3 storage). In git storage, photos
    /// are backed up by pushing, so this is empty.
    pub fn photo_backup(&self) -> PhotoBackup {
        if !self.config().image_storage.ignores_assets() {
            return PhotoBackup { count: 0, bytes: 0 };
        }
        let mut count = 0;
        let mut bytes = 0;
        for name in self.pending_uploads() {
            let (sha, ext) = name.split_once('.').unwrap_or((&name, "bin"));
            if let Ok(m) = fs::metadata(self.root.join(format::asset_rel_path(sha, ext))) {
                count += 1;
                bytes += m.len();
            }
        }
        PhotoBackup { count, bytes }
    }

    /// Every photo on this device, relative to the vault root
    /// (`assets/ab/….jpg`), for exporting.
    pub fn local_photos(&self) -> Vec<String> {
        self.asset_rel_paths()
    }

    /// Upload queued images to S3. Returns how many were uploaded.
    pub fn upload_pending(&self, s3_credentials: S3Credentials) -> Result<u32> {
        let cfg = self.config();
        if cfg.image_storage != ImageStorage::S3 {
            return Ok(0);
        }
        let Some(s3) = cfg.s3 else { return Ok(0) };
        let client = S3Client::new(s3, s3_credentials);
        let mut pending = self.pending_uploads();
        let mut n = 0;
        while let Some(name) = pending.first().cloned() {
            let (sha, ext) = name.split_once('.').unwrap_or((&name, "bin"));
            let rel = format::asset_rel_path(sha, ext);
            let abs = self.root.join(&rel);
            if abs.exists() && !client.exists(&rel)? {
                let bytes = fs::read(&abs)?;
                client.put(&rel, &bytes, images::content_type(ext))?;
                n += 1;
            }
            pending.remove(0);
            self.set_pending_uploads(&pending)?;
        }
        Ok(n)
    }

    /// Pull (fetch + fast-forward or merge) then push. Uploads pending images
    /// first when S3 credentials are given, so pushed entries never point at
    /// images that aren't in the bucket.
    pub fn sync(
        &self,
        credentials: GitCredentials,
        s3_credentials: Option<S3Credentials>,
    ) -> Result<SyncReport> {
        if let Some(open) = self.open_log() {
            return Err(CoreError::LogOpen {
                msg: format!("a log is open in {}; end it before syncing", open.project),
            });
        }
        let mut report = SyncReport::default();
        if let (ImageStorage::S3, Some(c)) = (self.config().image_storage, s3_credentials) {
            report.uploaded_assets = self.upload_pending(c)?;
        }
        let repo = self.repo();
        let branch = repo
            .head()
            .ok()
            .and_then(|h| h.shorthand().ok().map(str::to_string))
            .unwrap_or_else(|| "main".into());
        let mut remote = repo
            .find_remote("origin")
            .map_err(|_| CoreError::NotFound {
                msg: "remote 'origin'".into(),
            })?;
        let seen = Arc::new(Mutex::new(None));

        let mut fo = git2::FetchOptions::new();
        fo.remote_callbacks(remote_callbacks(credentials.clone(), seen.clone()));
        let refspec = format!("+refs/heads/{branch}:refs/remotes/origin/{branch}");
        let fetched = remote.fetch(&[&refspec], Some(&mut fo), None);
        report.host_key_sha256 = lock(&seen).clone();
        if let Err(e) = fetched {
            if let (Some(expected), Some(actual)) = (
                credentials.known_host_sha256.as_ref(),
                report.host_key_sha256.as_ref(),
            ) {
                if !expected.eq_ignore_ascii_case(actual) {
                    return Err(CoreError::HostKeyMismatch {
                        expected: expected.clone(),
                        actual: actual.clone(),
                    });
                }
            }
            return Err(e.into());
        }

        let local = repo.head()?.peel_to_commit()?;
        if let Ok(remote_ref) = repo.find_reference(&format!("refs/remotes/origin/{branch}")) {
            let theirs = remote_ref.peel_to_commit()?;
            let annotated = repo.find_annotated_commit(theirs.id())?;
            let (analysis, _) = repo.merge_analysis(&[&annotated])?;
            if analysis.is_fast_forward() {
                let (_, behind) = repo.graph_ahead_behind(local.id(), theirs.id())?;
                report.pulled_commits = behind as u32;
                let mut r = repo.find_reference(&format!("refs/heads/{branch}"))?;
                r.set_target(theirs.id(), "fieldlog: fast-forward")?;
                repo.checkout_head(Some(CheckoutBuilder::new().force()))?;
            } else if analysis.is_normal() {
                let (_, behind) = repo.graph_ahead_behind(local.id(), theirs.id())?;
                report.pulled_commits = behind as u32;
                let mut idx = repo.merge_commits(&local, &theirs, None)?;
                if idx.has_conflicts() {
                    report.conflict_copies = self.keep_both(&mut idx)?;
                }
                let tree = repo.find_tree(idx.write_tree_to(&repo)?)?;
                let sig = self.signature()?;
                repo.commit(
                    Some("HEAD"),
                    &sig,
                    &sig,
                    &format!("Merge origin/{branch}"),
                    &tree,
                    &[&local, &theirs],
                )?;
                repo.checkout_head(Some(CheckoutBuilder::new().force()))?;
                report.merged = true;
            }
        }

        // Push if we have anything the remote doesn't.
        let head = repo.head()?.peel_to_commit()?.id();
        let remote_has = repo
            .find_reference(&format!("refs/remotes/origin/{branch}"))
            .ok()
            .and_then(|r| r.target());
        if remote_has != Some(head) {
            let mut po = git2::PushOptions::new();
            let rejected = Arc::new(Mutex::new(None::<String>));
            let mut cb = remote_callbacks(credentials, seen.clone());
            let rej = rejected.clone();
            cb.push_update_reference(move |refname, status| {
                if let Some(s) = status {
                    *lock(&rej) = Some(format!("{refname}: {s}"));
                }
                Ok(())
            });
            po.remote_callbacks(cb);
            remote.push(
                &[format!("refs/heads/{branch}:refs/heads/{branch}")],
                Some(&mut po),
            )?;
            if let Some(msg) = lock(&rejected).take() {
                return Err(CoreError::Conflict { msg });
            }
            // Record what the remote now has.
            repo.reference(
                &format!("refs/remotes/origin/{branch}"),
                head,
                true,
                "fieldlog: push",
            )?;
            report.pushed = true;
        }
        report.host_key_sha256 = lock(&seen).clone();
        Ok(report)
    }

    /// Number of local commits not on the remote-tracking branch.
    pub fn unpushed_commits(&self) -> u32 {
        let repo = self.repo();
        let Ok(head) = repo.head() else { return 0 };
        let Some(branch) = head.shorthand().ok().map(str::to_string) else {
            return 0;
        };
        let Some(local) = head.target() else { return 0 };
        match repo
            .find_reference(&format!("refs/remotes/origin/{branch}"))
            .ok()
            .and_then(|r| r.target())
        {
            Some(remote) => repo
                .graph_ahead_behind(local, remote)
                .map(|(a, _)| a as u32)
                .unwrap_or(0),
            None => {
                let mut walk = match repo.revwalk() {
                    Ok(w) => w,
                    Err(_) => return 0,
                };
                if walk.push(local).is_err() {
                    return 0;
                }
                walk.count() as u32
            }
        }
    }

    pub fn has_remote(&self) -> bool {
        self.repo().find_remote("origin").is_ok()
    }

    pub fn set_remote(&self, url: String) -> Result<()> {
        let repo = self.repo();
        if repo.find_remote("origin").is_ok() {
            repo.remote_set_url("origin", &url)?;
        } else {
            repo.remote("origin", &url)?;
        }
        Ok(())
    }
}

/// True for git hosts with storage quotas (GitHub, GitLab, Bitbucket), where
/// committing photos fills the repo quickly.
#[uniffi::export]
pub fn is_hosted_git_remote(url: String) -> bool {
    let u = url.trim().to_lowercase();
    let host = if let Some(rest) = u.split_once("://").map(|(_, r)| r) {
        rest.split(['/', ':'])
            .next()
            .unwrap_or("")
            .rsplit('@')
            .next()
            .unwrap_or("")
            .to_string()
    } else if let Some((user_host, _)) = u.split_once(':') {
        // scp-like: git@github.com:owner/repo.git
        user_host.rsplit('@').next().unwrap_or("").to_string()
    } else {
        String::new()
    };
    ["github.com", "gitlab.com", "bitbucket.org"]
        .iter()
        .any(|h| host == *h || host.ends_with(&format!(".{h}")))
}

fn remote_callbacks(
    creds: GitCredentials,
    seen_host_key: Arc<Mutex<Option<String>>>,
) -> git2::RemoteCallbacks<'static> {
    let mut cb = git2::RemoteCallbacks::new();
    let mut attempts = 0u32;
    let c = creds.clone();
    cb.credentials(move |_url, username, allowed| {
        attempts += 1;
        if attempts > 3 {
            return Err(git2::Error::from_str("authentication failed"));
        }
        if allowed.contains(git2::CredentialType::SSH_KEY) {
            if let Some(key) = &c.ssh_private_key {
                return git2::Cred::ssh_key_from_memory(
                    username.unwrap_or("git"),
                    None,
                    key,
                    c.ssh_passphrase.as_deref(),
                );
            }
        }
        if allowed.contains(git2::CredentialType::USER_PASS_PLAINTEXT) {
            if let Some(pw) = &c.https_password {
                let user = c.https_username.as_deref().or(username).unwrap_or("git");
                return git2::Cred::userpass_plaintext(user, pw);
            }
        }
        if allowed.contains(git2::CredentialType::USERNAME) {
            return git2::Cred::username(username.unwrap_or("git"));
        }
        Err(git2::Error::from_str("no usable credentials configured"))
    });
    let pinned = creds.known_host_sha256.map(|s| s.to_lowercase());
    cb.certificate_check(move |cert, _host| {
        if let Some(hk) = cert.as_hostkey() {
            let Some(hash) = hk.hash_sha256() else {
                return Err(git2::Error::from_str("host key has no sha256"));
            };
            let actual = hex::encode(hash);
            *lock(&seen_host_key) = Some(actual.clone());
            return match &pinned {
                Some(p) if *p != actual => Err(git2::Error::from_str(&format!(
                    "host key mismatch: expected {p}, got {actual}"
                ))),
                _ => Ok(git2::CertificateCheckStatus::CertificateOk),
            };
        }
        // TLS: let libgit2/OpenSSL do normal chain validation.
        Ok(git2::CertificateCheckStatus::CertificatePassthrough)
    });
    cb
}
