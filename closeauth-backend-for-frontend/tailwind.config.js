/** @type {import('@tailwindcss/cli').Config} */
export default {
  content: [
    "./internal/templates/**/*.{templ,go}",
    "./static/**/*.html",
    "./cmd/**/*.go",
  ],
};
