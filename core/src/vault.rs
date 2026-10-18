//! A vault is one git repository holding projects and their entries.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, Once};

use chrono::{DateTime, FixedOffset, Local};
use git2::{build::CheckoutBuilder, Repository, Signature};
use serde::{Deserialize, Serialize};

use crate::error::{invalid, CoreError, Result};
use crate::format::{self, Block, FrontMatter, ASSETS_DIR, CONFIG_FILE, LOCAL_DIR, PROJECT_FILE};
use crate::images;
use crate::s3::{S3Client, S3Config, S3Credentials};

const ENTRY_TRAILER: &str = "FieldLog-Entry: ";
const UNDO_TRAILER: &str = "FieldLog-Undo: ";
const PENDING_UPLOADS: &str = "pending-uploads.txt";

// ---------------------------------------------------------------- records

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum ImageStorage {
    /// Images are committed to the repo under `assets/`.
    Git,
    /// Images live in S3; `assets/` is a local cache and is git-ignored.
    S3,
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
    pub created: String,
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
    if storage == ImageStorage::S3 {
        s.push_str(&format!("{ASSETS_DIR}/\n"));
    }
    fs::write(root.join(".gitignore"), s)?;
    Ok(())
}

fn trailer_value<'a>(message: &'a str, key: &str) -> Option<&'a str> {
    message
        .lines()
        .find_map(|l| l.strip_prefix(key))
        .map(str::trim)
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
        })
    }

    fn bootstrap(root: &Path, repo: &Repository, name: &str, author: &Author) -> Result<()> {
        let cfg = VaultConfig {
            name: name.to_string(),
            image_storage: ImageStorage::Git,
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

fn summarize(fm: &FrontMatter, body: &str, rel: String) -> EntrySummary {
    EntrySummary {
        id: fm.id.clone(),
        project: fm.project.clone(),
        path: rel,
        created: fm.created.to_rfc3339(),
        tags: fm.tags.clone(),
        summary: format::summary_line(body, 120),
        photo_count: fm.photos.len() as u32,
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

    /// Update and commit `fieldlog.toml` (and `.gitignore` for storage mode).
    pub fn set_config(&self, config: VaultConfig) -> Result<()> {
        if config.image_storage == ImageStorage::S3 && config.s3.is_none() {
            return Err(invalid("S3 image storage needs an [s3] section"));
        }
        let repo = self.repo();
        fs::write(self.root.join(CONFIG_FILE), toml::to_string(&config)?)?;
        write_gitignore(&self.root, config.image_storage)?;
        let mut remove = vec![];
        if config.image_storage == ImageStorage::S3 {
            // Stop tracking assets (files stay on disk as the local cache) and
            // queue anything not yet uploaded.
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
        }
        self.commit_paths(
            &repo,
            &[CONFIG_FILE.to_string(), ".gitignore".to_string()],
            &remove,
            "Update vault settings",
        )?;
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
                .filter_map(|p| self.load(p).map(|(fm, _, _)| fm.created))
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
        if self.config().image_storage == ImageStorage::S3 {
            let name = format!("{sha}.{ext}");
            let mut pending = self.pending_uploads();
            if !pending.contains(&name) {
                pending.push(name);
                self.set_pending_uploads(&pending)?;
            }
        }
        Ok(Block::Photo { sha256: sha, ext })
    }

    /// Write and commit one entry. `created` is RFC 3339; `None` means now.
    pub fn commit_entry(
        &self,
        project: String,
        blocks: Vec<Block>,
        tags: Vec<String>,
        source: Option<String>,
        created: Option<String>,
    ) -> Result<CommitInfo> {
        if !self.root.join(&project).is_dir() {
            return Err(CoreError::NotFound {
                msg: format!("project {project}"),
            });
        }
        let body = format::blocks_to_body(&blocks);
        if body.trim().is_empty() {
            return Err(invalid("empty entry"));
        }
        let created = match created {
            Some(s) => DateTime::parse_from_rfc3339(&s).map_err(|e| invalid(e.to_string()))?,
            None => Local::now().fixed_offset(),
        };
        let mut tags: Vec<String> = tags
            .iter()
            .map(|t| format::slugify(t))
            .filter(|t| !t.is_empty())
            .collect();
        tags.dedup();
        let photos: Vec<(String, String)> = blocks
            .iter()
            .filter_map(|b| match b {
                Block::Photo { sha256, ext } => Some((sha256.clone(), ext.clone())),
                _ => None,
            })
            .collect();

        let repo = self.repo();
        // Pick a path that doesn't exist yet.
        let (suffix, rel) = loop {
            let s = random_suffix();
            let r = format::entry_rel_path(&project, &created, &s);
            if !self.root.join(&r).exists() {
                break (s, r);
            }
        };
        let fm = FrontMatter {
            id: format::entry_id(&created, &suffix),
            project: project.clone(),
            created,
            tags,
            source,
            photos: photos.iter().map(|(s, _)| s.clone()).collect(),
            extra: Default::default(),
        };
        let abs = self.root.join(&rel);
        fs::create_dir_all(abs.parent().unwrap())?;
        fs::write(&abs, format::render(&fm, &body)?)?;

        let mut add = vec![rel.clone()];
        if self.config().image_storage == ImageStorage::Git {
            for (sha, ext) in &photos {
                let a = format::asset_rel_path(sha, ext);
                if !self.root.join(&a).exists() {
                    return Err(CoreError::NotFound {
                        msg: format!("photo {a}"),
                    });
                }
                add.push(a);
            }
        }
        let summary = format::summary_line(&body, 60);
        let title = if summary.is_empty() {
            format!("{} photo(s)", photos.len())
        } else {
            summary
        };
        let message = format!("{project}: {title}\n\n{ENTRY_TRAILER}{}\n", fm.id);
        let oid = match self.commit_paths(&repo, &add, &[], &message) {
            Ok(o) => o,
            Err(e) => {
                let _ = fs::remove_file(&abs);
                return Err(e);
            }
        };
        Ok(CommitInfo {
            commit: oid.to_string(),
            entry: summarize(&fm, &body, rel),
        })
    }

    /// Revert the most recent entry commit that hasn't been undone yet.
    /// Returns the entry that was removed.
    pub fn undo_last_entry(&self) -> Result<EntrySummary> {
        let repo = self.repo();
        let head = repo.head().map_err(|_| CoreError::NothingToUndo)?;
        let head_commit = head.peel_to_commit()?;
        let mut undone: Vec<String> = Vec::new();
        let mut target = None;
        let mut cur = Some(head_commit.clone());
        for _ in 0..500 {
            let Some(c) = cur else { break };
            let msg = c.message().unwrap_or("").to_string();
            if let Some(id) = trailer_value(&msg, UNDO_TRAILER) {
                undone.push(id.to_string());
            } else if let Some(id) = trailer_value(&msg, ENTRY_TRAILER) {
                if !undone.iter().any(|u| u == id) {
                    target = Some((c.clone(), id.to_string()));
                    break;
                }
            }
            cur = c.parent(0).ok();
        }
        let (commit, id) = target.ok_or(CoreError::NothingToUndo)?;

        // Describe the entry before it disappears.
        let tree = commit.tree()?;
        let parent_tree = commit.parent(0).ok().map(|p| p.tree()).transpose()?;
        let diff = repo.diff_tree_to_tree(parent_tree.as_ref(), Some(&tree), None)?;
        let mut summary = None;
        for d in diff.deltas() {
            if let Some(p) = d.new_file().path() {
                if p.extension().is_some_and(|x| x == "md") {
                    if let Some((fm, body, rel)) = self.load(&self.root.join(p)) {
                        summary = Some(summarize(&fm, &body, rel));
                    }
                }
            }
        }

        let mut index = repo.revert_commit(&commit, &head_commit, 0, None)?;
        if index.has_conflicts() {
            return Err(CoreError::Conflict {
                msg: "entry was edited after it was committed; undo it by hand".into(),
            });
        }
        let new_tree = repo.find_tree(index.write_tree_to(&repo)?)?;
        let sig = self.signature()?;
        let title = summary
            .as_ref()
            .map(|s| s.summary.clone())
            .unwrap_or_default();
        repo.commit(
            Some("HEAD"),
            &sig,
            &sig,
            &format!("Undo: {title}\n\n{UNDO_TRAILER}{id}\n"),
            &new_tree,
            &[&head_commit],
        )?;
        repo.checkout_head(Some(CheckoutBuilder::new().force()))?;
        summary.ok_or_else(|| CoreError::NotFound {
            msg: format!("entry {id}"),
        })
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
            created: fm.created.to_rfc3339(),
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

    /// Upload queued images to S3. Returns how many were uploaded.
    pub fn upload_pending(&self, s3_credentials: S3Credentials) -> Result<u32> {
        let cfg = self.config();
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
        let mut report = SyncReport::default();
        if let Some(c) = s3_credentials {
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
                    let paths: Vec<String> = idx
                        .conflicts()?
                        .filter_map(|c| c.ok())
                        .filter_map(|c| c.our.or(c.their))
                        .map(|e| String::from_utf8_lossy(&e.path).into_owned())
                        .collect();
                    return Err(CoreError::Conflict {
                        msg: paths.join(", "),
                    });
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
