/** 时间显示：今天的消息只给时分，跨天按「今天/昨天/前天/x月x日」分组。 */
const fmt = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });

export const clockLabel = (iso) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : fmt.format(d);
};

export function dayLabel(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const today = new Date();
  const startOf = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((startOf(today) - startOf(d)) / 86400000);
  if (diff === 0) return '今天';
  if (diff === 1) return '昨天';
  if (diff === 2) return '前天';
  if (d.getFullYear() === today.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`;
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
}

export const humanDate = (dateStr) => {
  if (!dateStr) return '';
  const [, m, d] = String(dateStr).split('-');
  return `${Number(m)}月${Number(d)}日`;
};

export const formatDuration = (ms) => {
  const sec = Math.max(0, Math.round((ms || 0) / 1000));
  return sec < 60 ? `${sec}″` : `${Math.floor(sec / 60)}′${String(sec % 60).padStart(2, '0')}″`;
};
