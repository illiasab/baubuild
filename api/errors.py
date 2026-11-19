class APIError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message

    def body(self):
        return {"error": {"code": self.code, "message": self.message}}
