FROM node:22-alpine

WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund

COPY webhook-sink.mjs mc-bot.mjs run-suite.mjs generate-report.mjs ./

ENTRYPOINT ["node"]
CMD ["run-suite.mjs"]
