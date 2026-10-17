export const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/$/, "");
export const OIDC_CLIENT_ID = process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "";
export const OIDC_SCOPE = process.env.NEXT_PUBLIC_OIDC_SCOPE ?? "openid profile email";
