FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
ENV PORT=8080 DB_FILE=/data/foggymap.db TRUST_PROXY=1
VOLUME /data
EXPOSE 8080
USER node
CMD ["node", "src/node-server.js"]
