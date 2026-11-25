/**
 * 前端文本工具（切句规则与 server/src/util/text.js 保持一致，但前端独立、不跨目录引服务端代码）。
 */

export function stripMarkdown(s) {
  return String(s || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/^#{1,6}\s*/gm, '')
    .trim();
}

export function splitSentences(text) {
  const cleaned = stripMarkdown(text).replace(/\r/g, '');
  const rough = cleaned
    .split(/(?<=[。！？…～!?；;\n])/g)
    .map((s) => s.trim())
    .filter(Boolean);

  const merged = [];
  for (const piece of rough) {
    const last = merged[merged.length - 1];
    if (last && [...last].length < 6) merged[merged.length - 1] = last + piece;
    else merged.push(piece);
  }

  return merged.flatMap((s) => {
    if ([...s].length <= 120) return [s];
    const chunks = [];
    let buf = '';
    for (const part of s.split(/(?<=[，,、])/g)) {
      if ([...buf].length + [...part].length > 120 && buf) {
        chunks.push(buf);
        buf = part;
      } else {
        buf += part;
      }
    }
    if (buf) chunks.push(buf);
    return chunks;
  });
}

export const charLen = (s) => [...(s || '')].length;
