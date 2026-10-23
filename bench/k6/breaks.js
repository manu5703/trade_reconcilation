// Load test of GET /api/breaks, the break-queue query the UI makes on every filter change.
//
// Run on the Compose network, so requests go straight to the service rather than through
// Docker Desktop's host port forwarding (which drops connections at ~1k new connections/s):
//
//   docker run --rm -i --network trade_reconcilation_default -e BASE_URL=http://recon-service:8080 \
//     -v "$PWD/bench/k6:/scripts" grafana/k6:1.1.0 run /scripts/breaks.js
//
// Each iteration picks one realistic queue query at random: the default view, a type or status
// filter, a counterparty, an age filter, a different sort, or a deep page.
import http from "k6/http";
import { check } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

const TYPES = ["MISSING_CONFIRM", "UNEXPECTED_CONFIRM", "QUANTITY_MISMATCH", "PRICE_OUT_OF_TOLERANCE",
  "SETTLE_DATE_MISMATCH", "SIDE_MISMATCH", "COUNTERPARTY_MISMATCH", "COMMISSION_DIFFERENCE", "DUPLICATE_CONFIRM"];
const SORTS = ["openedAt", "-openedAt", "type", "counterparty", "-status"];

export const options = {
  scenarios: {
    queue: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "10s", target: 25 },   // ramp up
        { duration: "40s", target: 25 },   // steady: 25 concurrent users, no think time
        { duration: "5s", target: 0 },
      ],
    },
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: {
    http_req_failed: ["rate<0.001"],
    http_req_duration: ["p(99)<500"],
  },
};

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function query() {
  const counterparty = `CP${String(1 + Math.floor(Math.random() * 30)).padStart(3, "0")}`;
  switch (Math.floor(Math.random() * 7)) {
    case 0: return "";
    case 1: return `type=${pick(TYPES)}`;
    case 2: return "status=OPEN";
    case 3: return `counterparty=${counterparty}`;
    case 4: return "minAgeHours=24";
    case 5: return `sort=${pick(SORTS)}`;
    default: return `page=${Math.floor(Math.random() * 200)}`;
  }
}

export default function () {
  const res = http.get(`${BASE_URL}/api/breaks?size=25&${query()}`, { tags: { name: "GET /api/breaks" } });
  check(res, {
    "status is 200": (r) => r.status === 200,
    "has items": (r) => r.status === 200 && Array.isArray(r.json("items")),
  });
}

// Writes the full summary as JSON next to the script, for docs/results.md.
export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    "/scripts/results.json": JSON.stringify(data, null, 2),  // copied to docs/results/k6-breaks.json
  };
}

function textSummary(data) {
  const d = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  const failed = data.metrics.http_req_failed.values.rate;
  return [
    "",
    `GET /api/breaks: ${reqs.count} requests, ${reqs.rate.toFixed(0)} req/s, ${(failed * 100).toFixed(2)}% failed`,
    `latency ms: p50 ${d.med.toFixed(1)}  p90 ${d["p(90)"].toFixed(1)}  p95 ${d["p(95)"].toFixed(1)}  p99 ${d["p(99)"].toFixed(1)}  max ${d.max.toFixed(1)}`,
    "",
  ].join("\n");
}
