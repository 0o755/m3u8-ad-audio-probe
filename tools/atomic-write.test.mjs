/* 验证规则文件的同目录原子提交、Windows 回退和异常恢复。 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { writeFileAtomically } from "./atomic-write.mjs";

test("同目录临时文件成功替换目标且不留事务文件", async (context) => {
  const directory = await createTemporaryDirectory(context);
  const target = join(directory, "rules.json");
  await writeFile(target, "old", "utf8");
  const renameDirectories = [];

  await writeFileAtomically(target, "new", {
    token: "success",
    io: {
      rename: async (source, destination) => {
        renameDirectories.push([dirname(source), dirname(destination)]);
        await rename(source, destination);
      },
    },
  });

  assert.equal(await readFile(target, "utf8"), "new");
  assert.ok(renameDirectories.every(([source, destination]) =>
    source === directory && destination === directory));
  assert.deepEqual(await transactionFiles(directory), []);
});

test("直接替换失败时清理临时文件并保持原目标", async (context) => {
  const directory = await createTemporaryDirectory(context);
  const target = join(directory, "rules.json");
  await writeFile(target, "old", "utf8");

  await assert.rejects(writeFileAtomically(target, "new", {
    token: "direct-failure",
    platform: "linux",
    io: { rename: async () => { throw fileError("EIO"); } },
  }), (error) => error.code === "EIO");

  assert.equal(await readFile(target, "utf8"), "old");
  assert.deepEqual(await transactionFiles(directory), []);
});

test("Windows 无法直接覆盖时通过备份完成可恢复替换", async (context) => {
  const directory = await createTemporaryDirectory(context);
  const target = join(directory, "rules.json");
  await writeFile(target, "old", "utf8");
  let firstReplace = true;

  await writeFileAtomically(target, "new", {
    token: "windows-success",
    platform: "win32",
    io: {
      rename: async (source, destination) => {
        if (firstReplace && destination === target) {
          firstReplace = false;
          throw fileError("EEXIST");
        }
        await rename(source, destination);
      },
    },
  });

  assert.equal(await readFile(target, "utf8"), "new");
  assert.deepEqual(await transactionFiles(directory), []);
});

test("Windows 回退提交失败时还原旧目标并清理临时文件", async (context) => {
  const directory = await createTemporaryDirectory(context);
  const target = join(directory, "rules.json");
  await writeFile(target, "old", "utf8");
  let targetAttempts = 0;

  await assert.rejects(writeFileAtomically(target, "new", {
    token: "windows-rollback",
    platform: "win32",
    io: {
      rename: async (source, destination) => {
        if (destination === target) {
          targetAttempts += 1;
          if (targetAttempts === 1) throw fileError("EEXIST");
          if (targetAttempts === 2) throw fileError("EIO");
        }
        await rename(source, destination);
      },
    },
  }), (error) => error.code === "EIO");

  assert.equal(await readFile(target, "utf8"), "old");
  assert.deepEqual(await transactionFiles(directory), []);
});

async function createTemporaryDirectory(context) {
  const directory = await mkdtemp(join(tmpdir(), "rules-atomic-write-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

async function transactionFiles(directory) {
  return (await readdir(directory)).filter((name) => name.endsWith(".tmp")
    || name.endsWith(".bak"));
}

function fileError(code) {
  return Object.assign(new Error(code), { code });
}
