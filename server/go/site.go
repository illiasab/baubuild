package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type site struct {
	api    *app
	static string
	docs   string
	store  string
	mu     sync.Mutex
	state  map[string]any
}

func newSite(api *app, root string) *site {
	s := &site{
		api:    api,
		static: filepath.Join(root, "static"),
		docs:   filepath.Join(root, "docs"),
		store:  filepath.Join(root, "data", "store.json"),
	}
	s.load()
	return s
}

func (s *site) load() {
	raw, err := os.ReadFile(s.store)
	if err != nil {
		s.state = defaultPortal()
		s.save()
		return
	}
	var state map[string]any
	if json.Unmarshal(raw, &state) != nil || state == nil {
		s.state = defaultPortal()
		return
	}
	s.state = state
}

func (s *site) save() {
	_ = os.MkdirAll(filepath.Dir(s.store), 0o755)
	raw, err := json.MarshalIndent(s.state, "", "  ")
	if err != nil {
		return
	}
	_ = os.WriteFile(s.store, raw, 0o644)
}

func (s *site) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	if s.serveDocs(w, r, path) {
		return
	}
	host := strings.ToLower(strings.Split(r.Host, ":")[0])
	if host == "api.baubuild.vesperconnect.org" || strings.HasPrefix(path, "/v1") || strings.HasPrefix(path, "/.well-known/") || strings.HasPrefix(path, "/p/") {
		s.api.ServeHTTP(w, r)
		return
	}
	if path == "/api/health" && r.Method == http.MethodGet {
		writeJSON(w, 200, map[string]any{"ok": true})
		return
	}
	if path == "/api/state" && r.Method == http.MethodGet {
		s.mu.Lock()
		body := publicPortal(s.state)
		s.mu.Unlock()
		writeJSON(w, 200, body)
		return
	}
	if path == "/api/action" && r.Method == http.MethodPost {
		s.action(w, r)
		return
	}
	if strings.HasPrefix(path, "/api/") {
		writeJSON(w, 404, map[string]any{"ok": false, "error": "Not found."})
		return
	}
	s.serveStatic(w, r, path)
}

func (s *site) action(w http.ResponseWriter, r *http.Request) {
	var action map[string]any
	if err := json.NewDecoder(io.LimitReader(r.Body, 100_000)).Decode(&action); err != nil || action == nil {
		writeJSON(w, 400, map[string]any{"ok": false, "error": "Send JSON."})
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	result := dispatchPortal(s.state, action)
	if ok, _ := result["ok"].(bool); ok {
		s.save()
	}
	result["state"] = publicPortal(s.state)
	code := 200
	if ok, _ := result["ok"].(bool); !ok {
		code = 400
	}
	writeJSON(w, code, result)
}

func (s *site) serveDocs(w http.ResponseWriter, r *http.Request, path string) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	var name string
	switch path {
	case "/docs", "/docs/":
		name = filepath.Join(s.docs, "index.html")
	case "/docs/openapi.json", "/openapi.json", "/v1/openapi.json":
		name = filepath.Join(s.docs, "openapi.json")
	default:
		return false
	}
	http.ServeFile(w, r, name)
	return true
}

func (s *site) serveStatic(w http.ResponseWriter, r *http.Request, path string) {
	switch path {
	case "/", "/index.html":
		path = "/index.html"
	case "/app", "/app/":
		path = "/app.html"
	}
	clean := filepath.Clean("/" + path)
	file := filepath.Join(s.static, clean)
	rel, err := filepath.Rel(s.static, file)
	if err != nil || strings.HasPrefix(rel, "..") {
		writeJSON(w, 404, map[string]any{"ok": false, "error": "Not found."})
		return
	}
	info, err := os.Stat(file)
	if err != nil || info.IsDir() {
		writeJSON(w, 404, map[string]any{"ok": false, "error": "Not found."})
		return
	}
	http.ServeFile(w, r, file)
}
