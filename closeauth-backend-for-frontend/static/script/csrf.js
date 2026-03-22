// HTMX CSRF Token Handler
// Add this to your static JavaScript files or inline in templates

document.addEventListener("DOMContentLoaded", function () {
  // Add CSRF token to all HTMX requests
  document.body.addEventListener("htmx:configRequest", function (evt) {
    // Get CSRF token from meta tag or cookie
    let csrfToken = getCSRFToken();
    if (csrfToken) {
      evt.detail.headers["X-CSRF-Token"] = csrfToken;
    }
  });

  // Add CSRF token to all regular forms
  const forms = document.querySelectorAll(
    'form[method="POST"], form[method="PUT"], form[method="PATCH"], form[method="DELETE"]'
  );
  forms.forEach(function (form) {
    if (!form.querySelector('input[name="csrf_token"]')) {
      let csrfToken = getCSRFToken();
      if (csrfToken) {
        let csrfInput = document.createElement("input");
        csrfInput.type = "hidden";
        csrfInput.name = "csrf_token";
        csrfInput.value = csrfToken;
        form.appendChild(csrfInput);
      }
    }
  });
});

function getCSRFToken() {
  // First try to get from existing form field
  const existingToken = document.querySelector('input[name="csrf_token"]');
  if (existingToken) {
    return existingToken.value;
  }

  // Try to get from meta tag (you can add this to your base template)
  const metaToken = document.querySelector('meta[name="csrf-token"]');
  if (metaToken) {
    return metaToken.getAttribute("content");
  }

  // Try to get from cookie
  const cookies = document.cookie.split(";");
  for (let cookie of cookies) {
    const [name, value] = cookie.trim().split("=");
    if (name === "csrf_token") {
      return decodeURIComponent(value);
    }
  }

  return null;
}
