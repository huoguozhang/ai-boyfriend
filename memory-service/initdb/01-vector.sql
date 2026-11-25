-- mem0 的 pgvector 后端要求库里已有 vector 扩展。
-- postgres 镜像只在「数据目录为空」的首次启动时执行本目录下的脚本，
-- 所以这里只是兜底：mem0 自己也会 CREATE EXTENSION IF NOT EXISTS vector。
CREATE EXTENSION IF NOT EXISTS vector;
