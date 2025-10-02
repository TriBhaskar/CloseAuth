/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./internal/templates/**/*.templ",
    "./internal/templates/**/*.go",
    "./static/**/*.html",
    "./cmd/**/*.go"
  ],
  theme: {
    extend: {},
  },
  plugins: [],
}