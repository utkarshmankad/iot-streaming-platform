import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { App } from './App';

test('renders the dashboard landmark and heading', () => {
  expect(renderToStaticMarkup(<App />)).toContain('<main><h1>Environmental IoT</h1></main>');
});
