const pending = new Map<string, { project: string; promise: Promise<unknown> }>();
const failures = new Map<string, { project: string; error: unknown }>();

/** Serialize chapter writes so an earlier autosave cannot arrive after a later save. */
export function queueDraftWrite<T>(project: string, chapter: string, write: () => Promise<T>): Promise<T> {
  const key = JSON.stringify([project, chapter]);
  const previous = pending.get(key)?.promise ?? Promise.resolve();
  const promise = previous.catch(() => undefined).then(write);
  pending.set(key, { project, promise });
  void promise.then(
    () => {
      failures.delete(key);
      if (pending.get(key)?.promise === promise) pending.delete(key);
    },
    (error: unknown) => {
      failures.set(key, { project, error });
      if (pending.get(key)?.promise === promise) pending.delete(key);
    },
  );
  return promise;
}

export async function awaitDraftWrites(project: string): Promise<void> {
  for (;;) {
    const writes = [...pending.values()].filter((entry) => entry.project === project);
    if (writes.length === 0) break;
    await Promise.all(writes.map((entry) => entry.promise));
  }
  const failure = [...failures.values()].find((entry) => entry.project === project);
  if (failure)
    throw new Error('前文章节尚未成功保存，请返回对应章节重试保存后再生成。', { cause: failure.error });
}
