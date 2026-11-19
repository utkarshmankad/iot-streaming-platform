import { createRoot } from 'react-dom/client';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Dashboard root is missing');
}

createRoot(container).render(<main><h1>Environmental IoT</h1></main>);
