// No-flash color-scheme init for the WeSort Manager web UI.
//
// MUI v9 persists the scheme choice in localStorage["mui-mode"] and applies
// it via documentElement[data-mui-color-scheme]. This external script (served
// same-origin, so it satisfies script-src 'self' without inline code) runs
// before the React bundle and sets the same attribute early, so a stored
// light choice does not flash dark on first paint. Unknown values fall back
// to the app default (dark).
(function () {
  try {
    var stored = localStorage.getItem("mui-mode");
    var mode = stored === "light" || stored === "dark" ? stored : "dark";
    document.documentElement.setAttribute("data-mui-color-scheme", mode);
  } catch (e) {
    document.documentElement.setAttribute("data-mui-color-scheme", "dark");
  }
})();
