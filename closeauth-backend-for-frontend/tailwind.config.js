/** @type {import('@tailwindcss/cli').Config} */
export default {
  darkMode: 'class',
  content: [
    "./internal/templates/**/*.{templ,go}",
    "./static/**/*.html",
    "./cmd/**/*.go",
  ],
};
