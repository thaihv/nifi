<%@ page contentType="text/html" pageEncoding="UTF-8" session="false" %>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.8.0/dist/leaflet.css" type="text/css"/>
<script type="text/javascript" src="https://unpkg.com/leaflet@1.8.0/dist/leaflet.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/wicket/1.3.6/wicket.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/wicket/1.3.6/wicket-leaflet.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/proj4js/2.8.0/proj4.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/proj4leaflet/1.0.2/proj4leaflet.min.js"></script>
<div id="map" style="height: 800px; width:800px; position: relative; padding: 0px; margin: 0 auto 0 auto;"></div>

<script> 
	var osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
	    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
	});
	var baseMaps = {
		    "OSM": osm
		};
	var geoType = "<%= request.getAttribute("geoType")%>";
	var crs = '<%= request.getAttribute("crs")%>';
	if (geoType == "Tiles"){
		
		var resourceUri = "<%= request.getAttribute("resourceUri")%>";
		var urlGeoTiles = resourceUri + "/{z}/{x}/{y}";
		var envelope    = <%= request.getAttribute("envelope")%>;
		var center      = <%= request.getAttribute("center")%>;
		var zoom_init   = <%= request.getAttribute("zoom_min")%>;
		var zoom_max    = <%= request.getAttribute("zoom_max")%>;
		
	    var myTiles = L.tileLayer(urlGeoTiles, {
	        minZoom: 1,
	        maxZoom: 20,
	        tms: false,
	        attribution: 'Generated by Jungdo-SETL engine'
	      });
	    
	    center = proj4(crs,'EPSG:4326',center);
	    minXMinY = proj4(crs,'EPSG:4326',[envelope[0][0], envelope[0][1]]);
	    maxXMaxY = proj4(crs,'EPSG:4326',[envelope[1][0], envelope[1][1]]);
		console.log(minXMinY);
		console.log(maxXMaxY);
		var map = L.map('map', {    
			center: [center[1], center[0]],
		    zoom: zoom_init,
		    layers: [osm, myTiles]});
		var overlayMaps = {
				"myTiles": myTiles
			};
		L.control.layers(baseMaps, overlayMaps).addTo(map);
		L.control.scale().addTo(map);
//		var map = L.map('map', {
//		    crs: L.CRS.Simple,
//		    center: center,
//		    zoom: zoom_init,
//		    layers: [myTiles]
//		});		
		
		envelope = [[minXMinY[1],minXMinY[0]],[maxXMaxY[1],maxXMaxY[0]]];

		console.log(envelope);
		map.fitBounds(envelope);
		
		map.on('click', function(e){
			  var coord = e.latlng;
			  var lat = coord.lat;
			  var lng = coord.lng;
			  var content = "You are at lat/lon: " + lat + "; " + lng;
			  var popup = L.popup().setLatLng(coord).setContent(content).openOn(map);
			  });	    
	}
	else{
		var map = L.map('map', {    
			center: [21.054385299481154 ,105.8254623413086],
		    zoom: 12,
		    layers: [osm]});
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
			
			var geoJsonLayer = L.geoJSON(geojson, {style: geomStyle}).addTo(map);
			var overlayMaps = {
					"myFeatures": geoJsonLayer
				};
			
			L.control.layers(baseMaps, overlayMaps).addTo(map);
			L.control.scale().addTo(map);
			
			map.fitBounds(geoJsonLayer.getBounds());
			console.log(geoJsonLayer.getBounds());
	}
</script>