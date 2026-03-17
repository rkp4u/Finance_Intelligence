FROM ollama/ollama:latest

WORKDIR /app

RUN echo '#!/bin/sh\n\
ollama serve &\n\
sleep 5\n\
ollama pull llama3.2:3b\n\
ollama pull nomic-embed-text\n\
tail -f /dev/null' > /app/start.sh && \
    chmod +x /app/start.sh

EXPOSE 11434

ENTRYPOINT ["/app/start.sh"]
