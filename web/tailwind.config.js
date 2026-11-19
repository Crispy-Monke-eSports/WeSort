/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "var(--background, 0 0% 100%)",
        mc: {
          grass: "#5d9c3f",
          dirt: "#8a5a33",
          stone: "#7d7d7d",
          gold: "#f5c542",
        },
      },
    },
  },
  plugins: [],
};
