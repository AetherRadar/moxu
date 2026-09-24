import { expect, it } from 'vitest';
import { awaitDraftWrites, queueDraftWrite } from './draft-write-barrier';

it('waits for previous-chapter saves and orders writes within a chapter', async () => {
  let release!: () => void;
  const order: string[] = [];
  const first = queueDraftWrite('ordered', 'V1C1', async () => {
    await new Promise<void>((resolve) => {
      release = resolve;
    });
    order.push('first');
  });
  const second = queueDraftWrite('ordered', 'V1C1', async () => {
    order.push('second');
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  let done = false;
  const barrier = awaitDraftWrites('ordered').then(() => {
    done = true;
  });
  expect(done).toBe(false);
  release();
  await Promise.all([first, second, barrier]);
  expect(order).toEqual(['first', 'second']);
  expect(done).toBe(true);
});

it('blocks generation after failed saves until the chapter is saved successfully', async () => {
  await expect(
    queueDraftWrite('failed', 'V1C1', async () => {
      throw new Error('offline');
    }),
  ).rejects.toThrow();
  await expect(awaitDraftWrites('failed')).rejects.toThrow('尚未成功保存');
  await queueDraftWrite('failed', 'V1C1', async () => undefined);
  await expect(awaitDraftWrites('failed')).resolves.toBeUndefined();
});

it('does not block other projects on a failed manuscript save', async () => {
  await expect(awaitDraftWrites('unrelated')).resolves.toBeUndefined();
});
