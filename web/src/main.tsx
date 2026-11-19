import { createRoot } from "react-dom/client";
import "./styles/globals.css";
import { App } from "./router";

const el = document.getElementById("root");
if (!el) throw new Error("missing #root");
createRoot(el).render(<App />);
