import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { AppProviders } from './app/providers';
import { initI18n } from './i18n';
import './styles/global.css';

const container = document.getElementById('root');
if (!container) throw new Error('#root 不存在，index.html 被改坏了');

// 先把翻译准备好再挂载，否则首帧会闪出 key 字面量
void initI18n().then(() => {
  createRoot(container).render(
    <StrictMode>
      <AppProviders>
        <App />
      </AppProviders>
    </StrictMode>,
  );
});
