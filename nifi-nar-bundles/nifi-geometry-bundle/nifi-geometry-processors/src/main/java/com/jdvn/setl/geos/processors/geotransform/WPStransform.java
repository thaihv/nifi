/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jdvn.setl.geos.processors.geotransform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.xml.namespace.QName;

import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.avro.AvroReaderWithEmbeddedSchema;
import org.apache.nifi.avro.AvroRecordReader;
import org.apache.nifi.avro.AvroTypeUtil;
import org.apache.nifi.avro.WriteAvroResultWithSchema;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyDescriptor.Builder;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.GeoAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.record.ListRecordSet;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.StopWatch;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.geotools.data.Parameter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.wps.WPSUtils;
import org.geotools.data.wps.WebProcessingService;
import org.geotools.data.wps.request.DescribeProcessRequest;
import org.geotools.data.wps.request.ExecuteProcessRequest;
import org.geotools.data.wps.response.DescribeProcessResponse;
import org.geotools.data.wps.response.ExecuteProcessResponse;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.ows.ServiceException;
import org.geotools.referencing.CRS;
import org.geotools.wps.WPSConfiguration;
import org.geotools.xsd.Encoder;
import org.geotools.xsd.EncoderDelegate;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.xml.sax.ContentHandler;
import org.xml.sax.ext.LexicalHandler;

import com.jdvn.setl.geos.processors.util.GeoUtils;
import com.jdvn.setl.geos.wpsservice.WPSService;

import net.opengis.wfs.FeatureCollectionType;
import net.opengis.wps10.DataType;
import net.opengis.wps10.InputDescriptionType;
import net.opengis.wps10.LiteralInputType;
import net.opengis.wps10.OutputDataType;
import net.opengis.wps10.ProcessDescriptionType;
import net.opengis.wps10.ProcessDescriptionsType;
import net.opengis.wps10.SupportedComplexDataInputType;

@Tags({ "Spatial Filter", "WKT", "WPS", "GML", "Attributes", "Geospatial" })
@CapabilityDescription("Transform data from a given flow file using WPS.")
@ReadsAttributes({ @ReadsAttribute(attribute = "", description = "") })
@WritesAttributes({ @WritesAttribute(attribute = "", description = "") })

public class WPStransform extends AbstractProcessor {

	private static AllowableValue[] capabilitiesIdentifiers;

    static final PropertyDescriptor WPS_STORE = new Builder()
            .name("wps-service")
            .displayName("WPS Service")
            .description("Specifies the Controller Service to use for fetch information from WPS service.")
            .identifiesControllerService(WPSService.class)
            .required(true)
            .build();

	public static final Relationship REL_SUCCESS = new Relationship.Builder()
			.name("success")
			.description("Flowfiles that have been successfully transformed are transferred to this relationship")
			.build();
	public static final Relationship REL_FAILURE = new Relationship.Builder()
			.name("failure")
			.description("Flowfiles that could not be transformed for some reason are transferred to this relationship")
			.build();

	public static PropertyDescriptor P_IDENTIFIER;
	private PropertyDescriptor P_INPUT;
	private List<PropertyDescriptor> properties;
	private WPSService gwps;
	
	private Set<Relationship> relationships;
	
	
	public void getInputDataFromProcessIdentifier(WebProcessingService wps, String processIden) throws ServiceException, IOException {
		
    	DescribeProcessRequest descRequest = wps.createDescribeProcessRequest();
    	descRequest.setIdentifier(processIden); // describe the buffer process

    	DescribeProcessResponse descResponse = wps.issueRequest(descRequest);
    	ProcessDescriptionsType processDesc = descResponse.getProcessDesc();
    	ProcessDescriptionType pdt = (ProcessDescriptionType) processDesc.getProcessDescription().get(0);
    	
        for (int i = 0; i < pdt.getDataInputs().getInput().size(); i++ ) {
        	InputDescriptionType idt = (InputDescriptionType) pdt.getDataInputs().getInput().get(i);
        	System.out.println(idt.getIdentifier().getValue());
        }

	}	

	List<String> getGeometryPropertyNames(SimpleFeatureCollection collection) {
		List<String> result = new ArrayList<String>();
		for (AttributeDescriptor ad : collection.getSchema().getAttributeDescriptors()) {
			if (ad instanceof GeometryDescriptor) {
				result.add(ad.getLocalName());
			}
		}
		return result;
	}	
	List<String> getGeometryPropertyNames(SimpleFeature feature) {
		List<String> result = new ArrayList<String>();
		for (AttributeDescriptor ad : feature.getFeatureType().getAttributeDescriptors()) {
			if (ad instanceof GeometryDescriptor) {
				result.add(ad.getLocalName());
			}
		}
		return result;
	}	
	@SuppressWarnings("rawtypes")
	public ArrayList<Record> getTransformedRecordsFromWPSProcess(WPSService wpsService, String processIden, String geojson_fc, Map<String, Object> map_LiteralData, RecordSchema recordSchema, String geoColumn) throws ServiceException, IOException {
		final ArrayList<Record> returnRs = new ArrayList<Record>();
		WebProcessingService wps = wpsService.getWps();
		
    	DescribeProcessRequest descRequest = wps.createDescribeProcessRequest();
    	descRequest.setIdentifier(processIden); // describe the buffer process

    	// send the request and get the ProcessDescriptionType bean to create a WPSFactory
    	DescribeProcessResponse descResponse = wps.issueRequest(descRequest);
    	ProcessDescriptionsType processDesc = descResponse.getProcessDesc();
    	ProcessDescriptionType pdt = (ProcessDescriptionType) processDesc.getProcessDescription().get(0);
    	
		ExecuteProcessRequest execRequest = wps.createExecuteProcessRequest();
		execRequest.setIdentifier(processIden);
		
        EList inputs = pdt.getDataInputs().getInput();
        Iterator iterator = inputs.iterator();
        while (iterator.hasNext()) {
            InputDescriptionType idt = (InputDescriptionType) iterator.next();
            String identifier = idt.getIdentifier().getValue();            
            List<EObject> list = new ArrayList<>();
        	if (idt.getIdentifier().getValue().equalsIgnoreCase("features")) {
        		DataType createdInput =  WPSUtils.createInputDataType(new CDATAEncoder(geojson_fc), WPSUtils.INPUTTYPE_COMPLEXDATA, null, "application/json");
        		list.add(createdInput);
        		execRequest.addInput(identifier, list);
        	}else if (idt.getIdentifier().getValue().equalsIgnoreCase("distance")){
                // our value is a single object so create a single datatype for it
        		Object inputValue = map_LiteralData.get("distance");
                DataType createdInput = WPSUtils.createInputDataType(inputValue, idt);                        
                list.add(createdInput);                		
                execRequest.addInput(identifier, list);
        	}else if (idt.getIdentifier().getValue().equalsIgnoreCase("preserveTopology")){
        		Object inputValue = map_LiteralData.get("preserveTopology");
                DataType createdInput = WPSUtils.createInputDataType(inputValue, idt);                        
                list.add(createdInput);                		
                execRequest.addInput(identifier, list);        		
        	}
        }
        try {        	

            ExecuteProcessResponse response;
            response = wps.issueRequest(execRequest);            
            if ((response.getExceptionResponse() == null) && (response.getExecuteResponse() != null))
            {
                if (response.getExecuteResponse().getStatus().getProcessSucceeded() != null)
                {
                    for (Object processOutput : response.getExecuteResponse().getProcessOutputs().getOutput())
                    {
                        OutputDataType wpsOutput = (OutputDataType) processOutput;
                        FeatureCollectionType fc = (FeatureCollectionType) wpsOutput.getData().getComplexData().getData().get(0);
                        DefaultFeatureCollection dfs = (DefaultFeatureCollection) fc.getFeature().get(0);
                        Iterator<SimpleFeature> itr = dfs.iterator();
                        while (itr.hasNext()) {
                        	SimpleFeature feature = itr.next();
                        	String fColumn = getGeometryPropertyNames(feature).get(0);
            				Map<String, Object> fieldMap = new HashMap<String, Object>();
            				for (int i = 0; i < feature.getAttributeCount(); i++) {
            					String key = feature.getFeatureType().getDescriptor(i).getName().getLocalPart();
            					Object value = feature.getAttribute(i);
            					if (key.equals(fColumn))
            						key = geoColumn;
            					fieldMap.put(key, value);						
            				}

            				Record r = new MapRecord(recordSchema, fieldMap);
            				returnRs.add(r);
                        }

                    }
                }
                return returnRs;
            } else {     
            	System.out.println(response.getExceptionResponse().getException().get(0).toString());
            }

        } catch (Exception e) {
        	System.out.println(e.getMessage());
        }
        return returnRs;

	}	
	
	@Override
	protected void init(final ProcessorInitializationContext context) {
		
		List<PropertyDescriptor> props = new ArrayList<>();
		props.add(WPS_STORE);
		properties = Collections.unmodifiableList(props);
		// relationships
		final Set<Relationship> procRels = new HashSet<>();
		procRels.add(REL_SUCCESS);
		procRels.add(REL_FAILURE);
		relationships = Collections.unmodifiableSet(procRels);

	}

	@OnScheduled
	public void setup(final ProcessContext context) {

	}

	@Override
	public Set<Relationship> getRelationships() {
		return relationships;
	}

	@Override
	protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return properties;
	}

	@Override
    public void onPropertyModified(final PropertyDescriptor descriptor, final String oldValue, final String newValue) {
    	System.out.println(descriptor.getDisplayName());
    	System.out.println(oldValue);
    	System.out.println(newValue);
    	if (descriptor.getName().equals("wps-service") && descriptor.getControllerServiceDefinition().equals(WPSService.class)){
    		if (this.getControllerServiceLookup().isControllerServiceEnabled(newValue)) {
    			
        		List<AllowableValue> listIdentifiers;
        		gwps = (WPSService) this.getControllerServiceLookup().getControllerService(newValue);      
        		
        		listIdentifiers = gwps.getWPSCapabilities();
    			capabilitiesIdentifiers = new AllowableValue[listIdentifiers.size()];
    			listIdentifiers.toArray(capabilitiesIdentifiers);
    			
    			P_IDENTIFIER = new PropertyDescriptor.Builder()
    					.name("process-id")
    					.displayName("Process Identifier")
    					.description("The Identifier of WPS process that is used to query information of input data and response.")
    					.required(true)
    					.allowableValues(capabilitiesIdentifiers)
    					.build();		
    	        final List<PropertyDescriptor> props = new ArrayList<>();
    	        props.add(WPS_STORE);
    	        props.add(P_IDENTIFIER);
    			properties = Collections.unmodifiableList(props);    			
    		}
    	}
    	if (descriptor.getName().equals("process-id") && newValue != null) {
    		try {
				List<PropertyDescriptor> props = new ArrayList<>();
    	        props.add(WPS_STORE);
    	        props.add(P_IDENTIFIER);
    	        
    			Map<String, Parameter<?>> parameters = new TreeMap<>();
    			parameters = gwps.getInputDataFromProcessIdentifier(newValue);
    			for (Map.Entry<String, Parameter<?>> entry : parameters.entrySet())
    			{
    				Parameter<?> value = entry.getValue();
    				if (!value.getType().getName().equals("org.locationtech.jts.geom.Geometry")) {
    					System.out.println("key: " + value.getName() + "; type: Literal Data");
            	        P_INPUT = new PropertyDescriptor.Builder()
            					.name(value.getName())
            					.displayName(entry.getKey())
            					.description(value.getDescription().toString())
            					.required(false)
            					.build();
            	        props.add(P_INPUT);
    				}
    				else 
    				{
    					System.out.println("key: " + value.getName() + "; type: Supported Complex Data");   					
    				}
    				        	        
    			}
    			properties = Collections.unmodifiableList(props);
			} catch (ServiceException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}

    }
	
	@Override
	public void onTrigger(final ProcessContext context, final ProcessSession session) {
		FlowFile flowFile = session.get();
		if (flowFile == null) {
			return;
		}
		final WPSService wpsService = context.getProperty(WPS_STORE).asControllerService(WPSService.class);
		final StopWatch stopWatch = new StopWatch(true);
		final ComponentLog logger = getLogger();
		try {

			session.read(flowFile, new InputStreamCallback() {
				@Override
				public void process(final InputStream in) {
					try {
						AvroRecordReader reader = new AvroReaderWithEmbeddedSchema(in);
						final String srs_source = flowFile.getAttributes().get(GeoAttributes.CRS.key());
						final CoordinateReferenceSystem crs_source = CRS.parseWKT(srs_source);
						final String identifier = context.getProperty(P_IDENTIFIER).evaluateAttributeExpressions(flowFile).getValue();
						SimpleFeatureCollection collection = GeoUtils.createSimpleFeatureCollectionFromNifiRecordsWithoutGeoFname("featurecolection", reader, crs_source);
						final RecordSchema recordSchema = GeoUtils.createFeatureRecordSchema(collection);
						
						Map<String, Object> map = new TreeMap<>();
						map.put("distance", 0.04);
						map.put("preserveTopology", true);
						String geoJson = GeoUtils.getGeojsonFromFeatureCollection(collection); 			
						String geoColumn = getGeometryPropertyNames(collection).get(0);
						List<Record> records = getTransformedRecordsFromWPSProcess(wpsService, identifier, geoJson, map, recordSchema, geoColumn);						

						FlowFile transformed = session.create(flowFile);
						transformed = session.write(transformed, new OutputStreamCallback() {
							@Override
							public void process(final OutputStream out) throws IOException {
								final Schema avroSchema = AvroTypeUtil.extractAvroSchema(recordSchema);
								@SuppressWarnings("resource")
								final RecordSetWriter writer = new WriteAvroResultWithSchema(avroSchema, out, CodecFactory.bzip2Codec());
								writer.write(new ListRecordSet(recordSchema, records));
								writer.flush();
							}
						});
						session.getProvenanceReporter().receive(transformed, flowFile.getAttributes().get(GeoUtils.GEO_URL), stopWatch.getElapsed(TimeUnit.MILLISECONDS));
						session.transfer(transformed, REL_SUCCESS);
						session.adjustCounter("Records Written", records.size(), false);
						
					} catch (IOException | FactoryException | ServiceException e) {
						logger.error("Could not transformed {} because {}", new Object[] { flowFile, e });
						session.transfer(flowFile, REL_FAILURE);
						return;
					}
				}
			});
			session.remove(flowFile);

		} catch (Exception e) {
			logger.error("Could not transformed {} because {}", new Object[] { flowFile, e });
			session.transfer(flowFile, REL_FAILURE);
			return;
		}
	}
    class WPSEncodeDelegate implements EncoderDelegate {

        private Object value;

        private QName qname;

        public WPSEncodeDelegate(Object value, QName qname) {
            this.value = value;
            this.qname = qname;
        }

        @Override
        public void encode(ContentHandler output) throws Exception {
            WPSConfiguration config = new WPSConfiguration();
            Encoder encoder = new Encoder(config);
            encoder.encode(value, qname, output);
        }
    }
    class CDATAEncoder implements EncoderDelegate {
        String cData;

        public CDATAEncoder(String cData) {
            this.cData = cData;
        }

        @Override
        public void encode(ContentHandler output) throws Exception {
            ((LexicalHandler) output).startCDATA();
            Reader r = new StringReader(cData);
            char[] buffer = new char[1024];
            int read;
            while ((read = r.read(buffer)) > 0) {
                output.characters(buffer, 0, read);
            }
            r.close();
            ((LexicalHandler) output).endCDATA();
        }
    }
}
