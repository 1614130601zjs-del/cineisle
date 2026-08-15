FROM node:20-alpine

WORKDIR /app

COPY server/package*.json ./
RUN npm install --package-lock=false --no-audit --no-fund && npm run check

COPY server/ ./

ENV NODE_ENV=production
EXPOSE 8787

CMD ["npm", "start"]
