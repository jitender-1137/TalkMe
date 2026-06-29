FROM node:20-slim

WORKDIR /app

# Pure-JS tfjs + sharp (prebuilt) — no native libtensorflow, builds on arm64 and x86.
COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

ENV PORT=8081
EXPOSE 8081
CMD ["node", "server.js"]
