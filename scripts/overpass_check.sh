#!/usr/bin/env bash
# Measures how long the public Overpass servers take for FoggyMap cell queries in German cities.
q() { # south west north east -> query (same as OverpassQuery.forBox)
cat <<Q
[out:json][timeout:40][maxsize:134217728];
(
  way["highway"]["highway"!~"^(proposed|construction|abandoned|platform|raceway|elevator|bus_stop|rest_area|services|razed)\$"]($1,$2,$3,$4);
  way["railway"~"^(rail|light_rail|subway|narrow_gauge)\$"]($1,$2,$3,$4);
  way["waterway"~"^(river|canal)\$"]($1,$2,$3,$4);
);
out body geom qt;
way["building"]($1,$2,$3,$4);
out tags geom qt;
Q
}
qroads() {
cat <<Q
[out:json][timeout:40];
(
  way["highway"]["highway"!~"^(proposed|construction|abandoned|platform|raceway|elevator|bus_stop|rest_area|services|razed)\$"]($1,$2,$3,$4);
  way["railway"~"^(rail|light_rail|subway|narrow_gauge)\$"]($1,$2,$3,$4);
  way["waterway"~"^(river|canal)\$"]($1,$2,$3,$4);
);
out body geom qt;
Q
}
for ep in https://overpass-api.de/api/interpreter https://overpass.private.coffee/api/interpreter https://overpass.kumi.systems/api/interpreter https://maps.mail.ru/osm/tools/overpass/api/interpreter; do
  for city in "Solingen 51.1675 7.0725 51.1835 7.0965" "Wuppertal 51.2535 7.1395 51.2695 7.1635" "Koeln 50.9335 6.9455 50.9495 6.9695"; do
    set -- $city
    for kind in full roads; do
      if [ $kind = full ]; then body=$(q $2 $3 $4 $5); else body=$(qroads $2 $3 $4 $5); fi
      out=$(curl -s -o /tmp/r.json -w '%{http_code} %{time_total}s %{size_download}B' --max-time 120 --data-urlencode "data=$body" "$ep")
      echo "::notice::$(echo $ep | cut -d/ -f3) $1 $kind -> $out"
    done
  done
done
