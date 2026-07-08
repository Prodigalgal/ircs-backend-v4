-- liquibase formatted sql

-- changeset zzp84:1770400000000-add-history-episode-id
-- 说明: 为历史记录添加 last_episode_id 以支持精确续播
-- 策略: 使用弱外键 (ON DELETE SET NULL)，防止集数删除导致历史记录丢失

ALTER TABLE member_watch_histories ADD COLUMN last_episode_id UUID;

ALTER TABLE member_watch_histories
    ADD CONSTRAINT fk_memhist_on_episode
        FOREIGN KEY (last_episode_id)
            REFERENCES episodes(id)
            ON DELETE SET NULL;