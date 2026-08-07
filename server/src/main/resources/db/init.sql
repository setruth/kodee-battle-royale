-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    user_id     BIGSERIAL PRIMARY KEY,
    username    VARCHAR(20) NOT NULL,
    name        VARCHAR(20) NOT NULL,               -- 显示名，注册时默认 = username
    password    VARCHAR(32) NOT NULL,               -- MD5 hex（32 字符）
    username_ci VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_ci ON users (username_ci);

-- 2. 对局历史表
CREATE TABLE IF NOT EXISTS matches (
    match_id     BIGSERIAL PRIMARY KEY,
    room_code    VARCHAR(6)  NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    duration_sec INT         NOT NULL,
    result       TEXT        NOT NULL,              -- JSON string：{board:[{name,color,hp,rank,isBot}], feed:[{text,color}]}
    settings     TEXT        NOT NULL DEFAULT '{}', -- 房间规则配置 (GameSettings JSON)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. 对局玩家关联表
CREATE TABLE IF NOT EXISTS match_players (
    match_id BIGINT NOT NULL REFERENCES matches(match_id) ON DELETE CASCADE,
    user_id  BIGINT REFERENCES users(user_id) ON DELETE SET NULL,  -- null = bot
    username VARCHAR(20) NOT NULL,                                 -- 快照存名，防用户注销后丢名
    rank     INT NOT NULL,
    is_bot   BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (match_id, username)
);

CREATE INDEX IF NOT EXISTS idx_match_players_user ON match_players (user_id);
