import { useState, type ReactNode } from 'react';
import { Button, Dialog, Field, Input, Select, Textarea } from '@/components/ui';
import { useCreateProject } from './queries';

const LANGUAGES = [
  { value: 'zh', label: '中文' },
  { value: 'en', label: 'English' },
] as const;

export function ProjectCreateDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (projectId: string) => void;
}): ReactNode {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [language, setLanguage] = useState<'zh' | 'en'>('zh');
  const create = useCreateProject();

  const trimmed = name.trim();

  const reset = (): void => {
    setName('');
    setDescription('');
    setLanguage('zh');
  };

  const submit = (): void => {
    if (trimmed === '') return;
    create.mutate(
      { name: trimmed, description: description.trim(), language },
      {
        onSuccess: (project) => {
          reset();
          onOpenChange(false);
          onCreated(project.id);
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
      title="新建项目"
      description="项目对应磁盘上一个独立的数据目录，卷、章、卡片与正典都存在其中。"
      dismissible={!create.isPending}
      footer={
        <>
          <Button tone="outline" onClick={() => onOpenChange(false)} disabled={create.isPending}>
            取消
          </Button>
          <Button tone="primary" onClick={submit} loading={create.isPending} disabled={trimmed === ''}>
            创建
          </Button>
        </>
      }
    >
      {/* 回车即提交，但多行简介里的回车要留给换行 */}
      <form
        className="flex flex-col gap-3.5"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <Field label="书名">
          {(props) => (
            <Input
              {...props}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="例如：山海旧梦"
              autoFocus
              maxLength={80}
            />
          )}
        </Field>

        <Field label="一句话简介" hint="会作为生成总纲时的初始设定，留空也可以之后再补。">
          {(props) => (
            <Textarea
              {...props}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={3}
              maxLength={500}
              placeholder="题材、主角与核心冲突"
            />
          )}
        </Field>

        <Field label="写作语言">
          {(props) => (
            <Select
              id={props.id}
              value={language}
              onValueChange={setLanguage}
              options={LANGUAGES.map((item) => ({ value: item.value, label: item.label }))}
            />
          )}
        </Field>
      </form>
    </Dialog>
  );
}
