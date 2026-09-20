-- V5：上下文压缩：早期历史滚动摘要（context_digest）与已压缩水位线（digest_watermark，记录摘要覆盖到的最后一条消息 id）
ALTER TABLE conversations ADD COLUMN context_digest TEXT;
ALTER TABLE conversations ADD COLUMN digest_watermark TEXT;
