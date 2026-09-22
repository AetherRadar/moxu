import { cn } from '@/lib/cn';

/**
 * 极简的变体解析器，替代 class-variance-authority。
 * 组件库只需要"按 key 查一张表"，不值得为此多一个依赖。
 */

type VariantMap = Record<string, Record<string, string>>;

type VariantKeys<M extends VariantMap> = {
  [K in keyof M]?: keyof M[K] | undefined;
};

export function variants<M extends VariantMap>(config: {
  base: string;
  variants: M;
  defaults: { [K in keyof M]: keyof M[K] };
}) {
  return (selected: VariantKeys<M> = {}, extra?: string): string => {
    const classes: string[] = [config.base];
    for (const key of Object.keys(config.variants) as (keyof M)[]) {
      const chosen = selected[key] ?? config.defaults[key];
      const value = config.variants[key]?.[chosen as string];
      if (value) classes.push(value);
    }
    return cn(classes, extra);
  };
}
