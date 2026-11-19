package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func mentorAI(text string, project map[string]any, history []map[string]any) (string, bool) {
	key := strings.TrimSpace(os.Getenv("MENTOR_API_KEY"))
	if key == "" {
		return "", false
	}
	model := strings.TrimSpace(os.Getenv("MENTOR_MODEL"))
	if model == "" {
		model = "gpt-4o-mini"
	}
	base := strings.TrimRight(strings.TrimSpace(os.Getenv("MENTOR_BASE_URL")), "/")
	if base == "" {
		base = "https://api.openai.com/v1"
	}
	messages := []map[string]string{{
		"role": "system",
		"content": "You are the BAU BUILD mentor. A student is asking about their project. Teach them the idea in plain language. Do not write the full solution. Keep the reply under 80 words. They write the change and push the branch.",
	}}
	if project != nil && str(project, "title") != "" {
		messages = append(messages, map[string]string{
			"role":    "system",
			"content": "Project: " + str(project, "title") + ". " + str(project, "description"),
		})
	}
	for _, row := range history {
		role := "user"
		if str(row, "role") == "mentor" {
			role = "assistant"
		}
		body := strings.TrimSpace(str(row, "body"))
		if body == "" {
			continue
		}
		messages = append(messages, map[string]string{"role": role, "content": body})
	}
	messages = append(messages, map[string]string{"role": "user", "content": text})
	payload, _ := json.Marshal(map[string]any{"model": model, "temperature": 0.2, "messages": messages})
	req, err := http.NewRequest(http.MethodPost, base+"/chat/completions", bytes.NewReader(payload))
	if err != nil {
		return "", false
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 20 * time.Second}
	res, err := client.Do(req)
	if err != nil {
		return "", false
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode >= 400 {
		return "", false
	}
	var parsed struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if json.Unmarshal(raw, &parsed) != nil || len(parsed.Choices) == 0 {
		return "", false
	}
	reply := strings.TrimSpace(parsed.Choices[0].Message.Content)
	if reply == "" {
		return "", false
	}
	if len(reply) > 2000 {
		reply = reply[:2000]
	}
	return reply, true
}
