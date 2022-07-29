<%@ page contentType="text/html" pageEncoding="UTF-8" session="false" %>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.8.0/dist/leaflet.css" type="text/css"/>
<script type="text/javascript" src="https://unpkg.com/leaflet@1.8.0/dist/leaflet.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/wicket/1.3.6/wicket.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/wicket/1.3.6/wicket-leaflet.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/proj4js/2.8.0/proj4.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/proj4leaflet/1.0.2/proj4leaflet.min.js"></script>
<div id="map" style="height: 800px; width:800px; position: relative; padding: 0px; margin: 0 auto 0 auto;"></div>

<script> 
	var map = L.map('map').setView([21.054385299481154 ,105.8254623413086], 12);
	var geoType = "<%= request.getAttribute("geoType")%>";
	var crs = '<%= request.getAttribute("crs")%>';
	if (geoType == "Tiles"){
		
		var resourceUri = "<%= request.getAttribute("resourceUri")%>";
		var urlGeoTiles = resourceUri + "/{z}/{x}/{y}";
		var envelope    = "<%= request.getAttribute("envelope")%>";
		var center      = <%= request.getAttribute("center")%>;
		var zoom_init   = <%= request.getAttribute("zoom_min")%>;
		var zoom_max    = <%= request.getAttribute("zoom_max")%>;
		
	    L.tileLayer(urlGeoTiles, {
	        minZoom: 1,
	        maxZoom: 20,
	        tms: false,
	        attribution: 'Generated by Jungdo-SETL engine'
	      }).addTo(map);
	    
	    center = proj4(crs,'EPSG:4326',center);
	    map.setView([center[1], center[0]], zoom_init);
		map.on('click', function(e){
			  var coord = e.latlng;
			  var lat = coord.lat;
			  var lng = coord.lng;
			  console.log("You clicked the map at latitude: " + lat + " and longitude: " + lng);
			  });	    
	}
	else{	
		L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
		    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
		}).addTo(map);		
		var geomStyle = {
			    "color": "#000",
			    "fillColor": "#ff7800",
			    "weight": 3
			};
		var featureCollection = <%= request.getAttribute("content")%>;
		var geojson = {
				  type: "FeatureCollection",
				  features: [],
				};
			function getGeometryField(json){
				for(var k in json){
					if (typeof json[k] == 'string')
						if (json[k].toUpperCase().includes("LINESTRING") || json[k].toUpperCase().includes("POINT") || json[k].toUpperCase().includes("POLYGON"))
							return k;
				}
				return null;
			}	
			for (var i = 0 ; i < featureCollection.length ; i++) {
				var wkt = new Wkt.Wkt();
				var geom_field = getGeometryField(featureCollection[i]);
				if (geom_field){
					wkt.read(featureCollection[i][geom_field]);
					var properties = {};
					for (name in featureCollection[i]) {
						if (name != geom_field)
							properties[name] = featureCollection[i][name];
					}
					for (var j = 0 ; j < wkt.components.length ; j++) {
						for (var k = 0 ; k < wkt.components[j].length ; k++) {
							if (wkt.components[j][k].length) {
								for (var p = 0 ; p < wkt.components[j][k].length ; p++) {
									wkt.components[j][k][p] = proj4(crs,'EPSG:4326',wkt.components[j][k][p]);
								}
							}
							else{
								wkt.components[j][k] = proj4(crs,'EPSG:4326',wkt.components[j][k]);
							}

						}
					}
					geojson.features.push({
					"type": "Feature",
					"geometry": wkt.toJson(),
					"properties": properties
					});
				}
			}
			let geoJsonLayer = L.geoJSON(geojson, {style: geomStyle}).addTo(map);
			map.fitBounds(geoJsonLayer.getBounds());
			console.log(geoJsonLayer.getBounds());
	}
</script>