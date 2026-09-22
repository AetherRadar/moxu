import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

/**
 * 最小可用的 lint 面：
 * - eslint 推荐规则 + typescript-eslint 推荐规则
 * - react-hooks 全套规则当 error——hooks 顺序错一个编辑器就崩，这条不降级
 * - react-refresh 只提醒，不挡构建
 */
export default tseslint.config(
  {
    ignores: ['dist', 'node_modules', 'coverage'],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.flat.recommended.rules,
      'react-refresh/only-export-components': 'off',
      // 项目没用 React Compiler；TanStack Virtual 的 useVirtualizer 天然不满足
      // 编译器记忆化约束，这规则只会产生改不掉的警告
      'react-hooks/incompatible-library': 'off',

      // 与现有代码风格对齐的放宽
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-empty-object-type': 'off',
    },
  },
);
