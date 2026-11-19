package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"
)

func main() {
	path := os.Getenv("BAU_DB")
	if path == "" {
		path = filepath.Join("data", "bau.sqlite")
	}
	db, err := openDB(path)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	host := os.Getenv("HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	root := os.Getenv("BAU_ROOT")
	if root == "" {
		root = "."
	}
	go seedGitHub(db)
	log.Fatal(http.ListenAndServe(host+":"+port, newSite(&app{db: db}, root)))
}
