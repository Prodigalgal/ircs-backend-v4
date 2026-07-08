alter table raw_videos
    add column if not exists aggregation_status_updated_at timestamp;

update raw_videos
set aggregation_status_updated_at = updated_at
where aggregation_status_updated_at is null;

alter table raw_videos
    alter column aggregation_status_updated_at set default now();

alter table raw_videos
    alter column aggregation_status_updated_at set not null;

create index if not exists idx_raw_videos_aggregation_status_updated_at
    on raw_videos (aggregation_status, aggregation_status_updated_at);
