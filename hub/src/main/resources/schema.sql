-- 多租戶資料模型（doc 07 §4）：owner（使用者或組織的命名空間）→ world → 維度 repo。
-- 只用 SQLite 與 PostgreSQL 共通的語法；主鍵為應用程式產生的 UUID 字串，時間為 epoch 毫秒。
CREATE TABLE IF NOT EXISTS owners (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL,
  display_name TEXT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL REFERENCES owners(id),
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  is_admin INTEGER NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS memberships (
  owner_id TEXT NOT NULL REFERENCES owners(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  role TEXT NOT NULL,
  PRIMARY KEY (owner_id, user_id)
);
CREATE TABLE IF NOT EXISTS tokens (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at BIGINT NOT NULL,
  expires_at BIGINT,
  last_used_at BIGINT,
  scope TEXT NOT NULL DEFAULT 'admin'
);
CREATE TABLE IF NOT EXISTS worlds (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL REFERENCES owners(id),
  slug TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT NOT NULL,
  visibility TEXT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE (owner_id, slug)
);
CREATE TABLE IF NOT EXISTS push_events (
  id TEXT PRIMARY KEY,
  world_id TEXT NOT NULL REFERENCES worlds(id),
  dimension TEXT NOT NULL,
  ref_name TEXT NOT NULL,
  old_head TEXT,
  new_head TEXT,
  snapshot TEXT,
  user_id TEXT,
  status TEXT NOT NULL,
  message TEXT,
  at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS push_events_world ON push_events(world_id, at);

-- Phase 4：SQL 只用 SQLite／PostgreSQL 共通語法；舊 token scope 由相容升級補上。
CREATE TABLE IF NOT EXISTS world_grants (
  world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id), role TEXT NOT NULL,
  PRIMARY KEY(world_id,user_id)
);
CREATE TABLE IF NOT EXISTS teams (
  id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES owners(id), slug TEXT NOT NULL,
  UNIQUE(owner_id,slug)
);
CREATE TABLE IF NOT EXISTS team_members (
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id),
  PRIMARY KEY(team_id,user_id)
);
CREATE TABLE IF NOT EXISTS team_grants (
  world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE, team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  role TEXT NOT NULL, PRIMARY KEY(world_id,team_id)
);
CREATE TABLE IF NOT EXISTS oauth_identities (
  provider TEXT NOT NULL, subject TEXT NOT NULL, user_id TEXT NOT NULL REFERENCES users(id),
  created_at BIGINT NOT NULL, PRIMARY KEY(provider,subject), UNIQUE(provider,user_id)
);
CREATE TABLE IF NOT EXISTS oauth_only_users (user_id TEXT PRIMARY KEY REFERENCES users(id));
CREATE TABLE IF NOT EXISTS account_emails (user_id TEXT PRIMARY KEY REFERENCES users(id), email TEXT NOT NULL UNIQUE);
CREATE TABLE IF NOT EXISTS registrations (
  id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE, expires_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS branch_rules (
  world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE, branch TEXT NOT NULL,
  pr_only INTEGER NOT NULL, reviews INTEGER NOT NULL,
  PRIMARY KEY(world_id,branch)
);
CREATE TABLE IF NOT EXISTS pull_requests (
  id TEXT PRIMARY KEY, world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE, number INTEGER NOT NULL,
  author_id TEXT NOT NULL REFERENCES users(id), source_branch TEXT NOT NULL, target_branch TEXT NOT NULL,
  title TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL, fingerprint TEXT,
  invalidated INTEGER NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  dimension TEXT NOT NULL DEFAULT 'minecraft:overworld', legacy INTEGER NOT NULL DEFAULT 0,
  merged_snapshot TEXT, merge_commits TEXT, merge_pending INTEGER NOT NULL DEFAULT 0, UNIQUE(world_id,number)
);
CREATE INDEX IF NOT EXISTS pull_requests_world ON pull_requests(world_id,created_at);
CREATE TABLE IF NOT EXISTS pr_choices (
  pr_id TEXT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE, region_id INTEGER NOT NULL,
  choice TEXT NOT NULL, PRIMARY KEY(pr_id,region_id)
);
CREATE TABLE IF NOT EXISTS pr_reviews (
  pr_id TEXT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id),
  decision TEXT NOT NULL, fingerprint TEXT NOT NULL, at BIGINT NOT NULL, PRIMARY KEY(pr_id,user_id)
);
CREATE TABLE IF NOT EXISTS comments (
  id TEXT PRIMARY KEY, world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
  pr_id TEXT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES users(id),
  parent_id TEXT REFERENCES comments(id), body TEXT NOT NULL, dimension TEXT,
  project_dimension TEXT NOT NULL DEFAULT 'minecraft:overworld',
  x INTEGER, y INTEGER, z INTEGER, max_x INTEGER, max_y INTEGER, max_z INTEGER,
  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS comments_world ON comments(world_id,created_at);
CREATE TABLE IF NOT EXISTS releases (
  id TEXT PRIMARY KEY, world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
  tag TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, commits TEXT NOT NULL,
  author_id TEXT NOT NULL REFERENCES users(id), created_at BIGINT NOT NULL, UNIQUE(world_id,tag)
);
CREATE TABLE IF NOT EXISTS hub_events (
  id TEXT PRIMARY KEY, world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
  type TEXT NOT NULL, payload TEXT NOT NULL, at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS notifications (
  id TEXT PRIMARY KEY, event_id TEXT NOT NULL REFERENCES hub_events(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id), seen INTEGER NOT NULL DEFAULT 0, at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS webhooks (
  id TEXT PRIMARY KEY, world_id TEXT NOT NULL REFERENCES worlds(id) ON DELETE CASCADE,
  url TEXT NOT NULL, secret TEXT NOT NULL, events TEXT NOT NULL, enabled INTEGER NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS webhook_deliveries (
  id TEXT PRIMARY KEY, webhook_id TEXT NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
  event_id TEXT NOT NULL REFERENCES hub_events(id) ON DELETE CASCADE,
  attempt INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, next_at BIGINT NOT NULL, response_code INTEGER, error TEXT,
  UNIQUE(webhook_id,event_id)
);
CREATE INDEX IF NOT EXISTS webhook_due ON webhook_deliveries(status,next_at);
