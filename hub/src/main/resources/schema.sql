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
  last_used_at BIGINT
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
