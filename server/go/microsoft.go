package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// microsoftIdentity redeems a public-client PKCE code with Entra ID.
// No client secret is sent: mobile and desktop redirect URIs are public clients.
func microsoftIdentity(code, verifier, redirect string) (string, string, error) {
	clientID := strings.TrimSpace(os.Getenv("MICROSOFT_CLIENT_ID"))
	if clientID == "" {
		return "", "", fail(503, "microsoft_not_configured", "Microsoft sign-in is not configured on this server.")
	}
	if code == "" || verifier == "" || redirect == "" {
		return "", "", fail(400, "invalid_body", "code, code_verifier, and redirect_uri are required.")
	}
	tenant := strings.TrimSpace(os.Getenv("MICROSOFT_TENANT"))
	if tenant == "" {
		tenant = "common"
	}
	form := url.Values{
		"client_id":     {clientID},
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirect},
		"code_verifier": {verifier},
		"scope":         {"openid profile email User.Read"},
	}
	tokenURL := "https://login.microsoftonline.com/" + url.PathEscape(tenant) + "/oauth2/v2.0/token"
	req, err := http.NewRequest(http.MethodPost, tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	client := &http.Client{Timeout: 15 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	var token map[string]any
	if json.Unmarshal(raw, &token) != nil || res.StatusCode >= 400 || asString(token["access_token"]) == "" {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	graph, err := http.NewRequest(http.MethodGet, "https://graph.microsoft.com/v1.0/me", nil)
	if err != nil {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	graph.Header.Set("Authorization", "Bearer "+asString(token["access_token"]))
	profileRes, err := client.Do(graph)
	if err != nil {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	defer profileRes.Body.Close()
	raw, _ = io.ReadAll(io.LimitReader(profileRes.Body, 1<<20))
	var profile map[string]any
	if json.Unmarshal(raw, &profile) != nil || profileRes.StatusCode >= 400 {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not accept that sign-in code.")
	}
	email := strings.TrimSpace(asString(profile["mail"]))
	if email == "" {
		email = strings.TrimSpace(asString(profile["userPrincipalName"]))
	}
	if email == "" {
		return "", "", fail(401, "microsoft_rejected", "Microsoft did not return an email address.")
	}
	name := strings.TrimSpace(asString(profile["displayName"]))
	if name == "" {
		name = email
	}
	return email, name, nil
}
