import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import { AnimeEyePreview } from './preview/AnimeEyePreview';

const isEyePreview = new URLSearchParams(window.location.search).get('preview') === 'eye';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {isEyePreview ? <AnimeEyePreview /> : <App />}
  </React.StrictMode>,
);
