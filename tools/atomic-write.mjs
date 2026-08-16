/* 以同目录临时文件提交 JSON，并在 Windows 覆盖失败时保留可恢复的旧文件。 */
import { randomUUID } from "node:crypto";
import { open, rename, stat, unlink } from "node:fs/promises";
import { basename, dirname, join, resolve } from "node:path";

const DEFAULT_IO = Object.freeze({ open, rename, stat, unlink });
const WINDOWS_REPLACE_ERRORS = new Set(["EACCES", "EEXIST", "ENOTEMPTY", "EPERM"]);

export async function writeFileAtomically(targetFile, content, options = {}) {
  const io = { ...DEFAULT_IO, ...options.io };
  const targetPath = resolve(targetFile);
  const token = options.token ?? `${process.pid}-${randomUUID()}`;
  const directory = dirname(targetPath);
  const stem = basename(targetPath);
  const temporaryPath = join(directory, `.${stem}.${token}.tmp`);
  const backupPath = join(directory, `.${stem}.${token}.bak`);
  let handle;
  try {
    const mode = await targetMode(io, targetPath);
    handle = await io.open(temporaryPath, "wx", mode);
    await handle.writeFile(content, "utf8");
    await handle.sync();
    await handle.close();
    handle = undefined;
    await commitTemporaryFile(temporaryPath, targetPath, backupPath, {
      io,
      platform: options.platform ?? process.platform,
    });
  } finally {
    if (handle !== undefined) await closeQuietly(handle);
    await unlinkIfPresent(io, temporaryPath);
  }
}

export async function commitTemporaryFile(temporaryPath, targetPath, backupPath, options = {}) {
  const io = { ...DEFAULT_IO, ...options.io };
  try {
    await io.rename(temporaryPath, targetPath);
    return;
  } catch (error) {
    if (!needsWindowsFallback(error, options.platform ?? process.platform)) throw error;
  }

  await io.rename(targetPath, backupPath);
  try {
    await io.rename(temporaryPath, targetPath);
  } catch (replacementError) {
    try {
      await io.rename(backupPath, targetPath);
    } catch (restoreError) {
      throw new AggregateError([replacementError, restoreError],
        `规则文件替换及还原均失败，旧文件保留在：${backupPath}`);
    }
    throw replacementError;
  }

  // 新目标已经发布，备份清理失败不应把成功提交误报为失败。
  await unlinkIfPresent(io, backupPath, true);
}

function needsWindowsFallback(error, platform) {
  return platform === "win32" && WINDOWS_REPLACE_ERRORS.has(error?.code);
}

async function targetMode(io, targetPath) {
  try {
    const metadata = await io.stat(targetPath);
    return metadata.mode & 0o777;
  } catch (error) {
    if (error?.code === "ENOENT") return 0o666;
    throw error;
  }
}

async function closeQuietly(handle) {
  try {
    await handle.close();
  } catch {
    // 后续仍需清理未提交的临时文件。
  }
}

async function unlinkIfPresent(io, path, ignoreOtherErrors = false) {
  try {
    await io.unlink(path);
  } catch (error) {
    if (error?.code !== "ENOENT" && !ignoreOtherErrors) throw error;
  }
}
