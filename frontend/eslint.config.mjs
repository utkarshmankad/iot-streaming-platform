import parser from '@babel/eslint-parser';

export default [{
  files: ['src/**/*.{ts,tsx}'],
  languageOptions: {
    parser,
    parserOptions: {
      requireConfigFile: false,
      babelOptions: {
        parserOpts: { plugins: ['typescript', 'jsx'] },
      },
    },
  },
  rules: {
    'no-debugger': 'error',
    'no-duplicate-imports': 'error',
    'no-unreachable': 'error',
  },
}];
