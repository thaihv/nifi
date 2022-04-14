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
package com.jdvn.setl.geos.processors.shapefile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.LogLevel;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.map.FeatureLayer;
import org.geotools.map.Layer;
import org.geotools.map.MapContent;
import org.geotools.map.MapViewport;
import org.geotools.referencing.CRS;
import org.geotools.renderer.GTRenderer;
import org.geotools.renderer.lite.StreamingRenderer;
import org.geotools.styling.SLD;
import org.geotools.styling.Style;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;


@Tags({ "shape file", "wkt", "json", "geospatial" })
@CapabilityDescription("Read data from a given shape file and represent geospatial data in WKT format.")
@SeeAlso({ ShpWriter.class })
@ReadsAttributes({ @ReadsAttribute(attribute = "", description = "") })
@WritesAttributes({ @WritesAttribute(attribute = "", description = "") })

public class ShpReader extends AbstractProcessor {

	static final AllowableValue COMPLETION_NONE = new AllowableValue("None", "None", "Leave the file as-is");
	static final AllowableValue COMPLETION_MOVE = new AllowableValue("Move File", "Move File",
			"Moves the file to the directory specified by the <Move Destination Directory> property");
	static final AllowableValue COMPLETION_DELETE = new AllowableValue("Delete File", "Delete File",
			"Deletes the original file from the file system");

	static final AllowableValue CONFLICT_REPLACE = new AllowableValue("Replace File", "Replace File",
			"The newly ingested file should replace the existing file in the Destination Directory");
	static final AllowableValue CONFLICT_KEEP_INTACT = new AllowableValue("Keep Existing", "Keep Existing",
			"The existing file should in the Destination Directory should stay intact and the newly "
					+ "ingested file should be deleted");
	static final AllowableValue CONFLICT_FAIL = new AllowableValue("Fail", "Fail",
			"The existing destination file should remain intact and the incoming FlowFile should be routed to failure");
	static final AllowableValue CONFLICT_RENAME = new AllowableValue("Rename", "Rename",
			"The existing destination file should remain intact. The newly ingested file should be moved to the "
					+ "destination directory but be renamed to a random filename");

	static final PropertyDescriptor FILENAME = new PropertyDescriptor.Builder().name("File to Fetch")
			.description("The fully-qualified filename of the file to fetch from the file system")
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.defaultValue("${absolute.path}/${filename}").required(true).build();
	static final PropertyDescriptor COMPLETION_STRATEGY = new PropertyDescriptor.Builder().name("Completion Strategy")
			.description(
					"Specifies what to do with the original file on the file system once it has been pulled into NiFi")
			.expressionLanguageSupported(ExpressionLanguageScope.NONE)
			.allowableValues(COMPLETION_NONE, COMPLETION_MOVE, COMPLETION_DELETE)
			.defaultValue(COMPLETION_NONE.getValue()).required(true).build();
	static final PropertyDescriptor MOVE_DESTINATION_DIR = new PropertyDescriptor.Builder()
			.name("Move Destination Directory")
			.description(
					"The directory to the move the original file to once it has been fetched from the file system. This property is ignored unless the Completion Strategy is set to \"Move File\". "
							+ "If the directory does not exist, it will be created.")
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR).required(false).build();
	static final PropertyDescriptor CONFLICT_STRATEGY = new PropertyDescriptor.Builder().name("Move Conflict Strategy")
			.description(
					"If Completion Strategy is set to Move File and a file already exists in the destination directory with the same name, this property specifies "
							+ "how that naming conflict should be resolved")
			.allowableValues(CONFLICT_RENAME, CONFLICT_REPLACE, CONFLICT_KEEP_INTACT, CONFLICT_FAIL)
			.defaultValue(CONFLICT_RENAME.getValue()).required(true).build();
	static final PropertyDescriptor FILE_NOT_FOUND_LOG_LEVEL = new PropertyDescriptor.Builder()
			.name("Log level when file not found")
			.description("Log level to use in case the file does not exist when the processor is triggered")
			.allowableValues(LogLevel.values()).defaultValue(LogLevel.ERROR.toString()).required(true).build();
    static final PropertyDescriptor PERM_DENIED_LOG_LEVEL = new PropertyDescriptor.Builder()
            .name("Log level when permission denied")
            .description("Log level to use in case user " + System.getProperty("user.name") + " does not have sufficient permissions to read the file")
            .allowableValues(LogLevel.values())
            .defaultValue(LogLevel.ERROR.toString())
            .required(true)
            .build();
	static final Relationship REL_SUCCESS = new Relationship.Builder().name("success")
			.description("Shape file is routed to success").build();
	static final Relationship REL_NOT_FOUND = new Relationship.Builder().name("not.found").description(
			"Any FlowFile that could not be fetched from the file system because the file could not be found will be transferred to this Relationship.")
			.build();
	static final Relationship REL_PERMISSION_DENIED = new Relationship.Builder().name("permission.denied").description(
			"Any FlowFile that could not be fetched from the file system due to the user running NiFi not having sufficient permissions will be transferred to this Relationship.")
			.build();
	static final Relationship REL_FAILURE = new Relationship.Builder().name("failure").description(
			"Any FlowFile that could not be fetched from the file system for any reason other than insufficient permissions or the file not existing will be transferred to this Relationship.")
			.build();
	private List<PropertyDescriptor> descriptors;

	private Set<Relationship> relationships;

	@Override
	protected void init(final ProcessorInitializationContext context) {
		descriptors = new ArrayList<>();
		descriptors.add(FILENAME);
		descriptors.add(COMPLETION_STRATEGY);
		descriptors.add(MOVE_DESTINATION_DIR);
		descriptors.add(CONFLICT_STRATEGY);
		descriptors.add(FILE_NOT_FOUND_LOG_LEVEL);
		descriptors.add(PERM_DENIED_LOG_LEVEL);
		descriptors = Collections.unmodifiableList(descriptors);

		relationships = new HashSet<>();
		relationships.add(REL_SUCCESS);
		relationships.add(REL_NOT_FOUND);
		relationships.add(REL_PERMISSION_DENIED);
		relationships.add(REL_FAILURE);
		relationships = Collections.unmodifiableSet(relationships);
	}

	@Override
	public Set<Relationship> getRelationships() {
		return this.relationships;
	}

	@Override
	public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
		return descriptors;
	}

	@OnScheduled
	public void onScheduled(final ProcessContext context) {

	}

	@Override
	public void onTrigger(final ProcessContext context, final ProcessSession session) {
		FlowFile flowFile = session.get();
		// TODO implement
        final StopWatch stopWatch = new StopWatch(true);
        final String filename = context.getProperty(FILENAME).evaluateAttributeExpressions(flowFile).getValue();
        final LogLevel levelFileNotFound = LogLevel.valueOf(context.getProperty(FILE_NOT_FOUND_LOG_LEVEL).getValue());
        final LogLevel levelPermDenied = LogLevel.valueOf(context.getProperty(PERM_DENIED_LOG_LEVEL).getValue());
        final File file = new File(filename);

        // Verify that file system is reachable and file exists
        Path filePath = file.toPath();
        if (!Files.exists(filePath) && !Files.notExists(filePath)){ // see https://docs.oracle.com/javase/tutorial/essential/io/check.html for more details
            getLogger().log(levelFileNotFound, "Could not fetch file {} from file system for {} because the existence of the file cannot be verified; routing to failure",
                    new Object[] {file, flowFile});
            session.transfer(session.penalize(flowFile), REL_FAILURE);
            return;
        } else if (!Files.exists(filePath)) {
            getLogger().log(levelFileNotFound, "Could not fetch file {} from file system for {} because the file does not exist; routing to not.found", new Object[] {file, flowFile});
            session.getProvenanceReporter().route(flowFile, REL_NOT_FOUND);
            session.transfer(session.penalize(flowFile), REL_NOT_FOUND);
            return;
        }

        // Verify read permission on file
        final String user = System.getProperty("user.name");
        if (!isReadable(file)) {
            getLogger().log(levelPermDenied, "Could not fetch file {} from file system for {} due to user {} not having sufficient permissions to read the file; routing to permission.denied",
                new Object[] {file, flowFile, user});
            session.getProvenanceReporter().route(flowFile, REL_PERMISSION_DENIED);
            session.transfer(session.penalize(flowFile), REL_PERMISSION_DENIED);
            return;
        }

        // If configured to move the file and fail if unable to do so, check that the existing file does not exist and that we have write permissions
        // for the parent file.
        final String completionStrategy = context.getProperty(COMPLETION_STRATEGY).getValue();
        final String targetDirectoryName = context.getProperty(MOVE_DESTINATION_DIR).evaluateAttributeExpressions(flowFile).getValue();
        if (targetDirectoryName != null) {
            final File targetDir = new File(targetDirectoryName);
            if (COMPLETION_MOVE.getValue().equalsIgnoreCase(completionStrategy)) {
                if (targetDir.exists() && (!isWritable(targetDir) || !isDirectory(targetDir))) {
                    getLogger().error("Could not fetch file {} from file system for {} because Completion Strategy is configured to move the original file to {}, "
                        + "but that is not a directory or user {} does not have permissions to write to that directory",
                        new Object[] {file, flowFile, targetDir, user});
                    session.transfer(flowFile, REL_FAILURE);
                    return;
                }

                if (!targetDir.exists()) {
                    try {
                        Files.createDirectories(targetDir.toPath());
                    } catch (Exception e) {
                        getLogger().error("Could not fetch file {} from file system for {} because Completion Strategy is configured to move the original file to {}, "
                                        + "but that directory does not exist and could not be created due to: {}",
                                new Object[] {file, flowFile, targetDir, e.getMessage()}, e);
                        session.transfer(flowFile, REL_FAILURE);
                        return;
                    }
                }

                final String conflictStrategy = context.getProperty(CONFLICT_STRATEGY).getValue();

                if (CONFLICT_FAIL.getValue().equalsIgnoreCase(conflictStrategy)) {
                    final File targetFile = new File(targetDir, file.getName());
                    if (targetFile.exists()) {
                        getLogger().error("Could not fetch file {} from file system for {} because Completion Strategy is configured to move the original file to {}, "
                            + "but a file with name {} already exists in that directory and the Move Conflict Strategy is configured for failure",
                            new Object[] {file, flowFile, targetDir, file.getName()});
                        session.transfer(flowFile, REL_FAILURE);
                        return;
                    }
                }
            }
        }
        
        
        Map<String, Object> mapAttrs = new HashMap<>();
        try {
			mapAttrs.put("url", file.toURI().toURL());
	        DataStore dataStore = DataStoreFinder.getDataStore(mapAttrs);
	        String typeName = dataStore.getTypeNames()[0];

	        FeatureSource<SimpleFeatureType, SimpleFeature> featureSource = dataStore.getFeatureSource(typeName);
//	        Filter filter = Filter.INCLUDE; // ECQL.toFilter("BBOX(THE_GEOM, 10,20,30,40)")
//
//	        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = featureSource.getFeatures(filter);
//	        try (FeatureIterator<SimpleFeature> features = collection.features()) {
//	            while (features.hasNext()) {
//	                SimpleFeature feature = features.next();
//	                System.out.print(feature.getID());
//	                System.out.print(": ");
//	                System.out.println(feature.getDefaultGeometryProperty().getValue());
//	            }
//	        }		
	        
	        Style style = SLD.createSimpleStyle(featureSource.getSchema());
	        Layer layer = new FeatureLayer(featureSource, style);
	        
	        //Step 1: Create map
	        MapContent map = new MapContent();
	        map.setTitle("Geometry block");

	        //Step 2: Set projection
	        CoordinateReferenceSystem crs = CRS.decode("EPSG:5179"); 
	        MapViewport vp = map.getViewport();
	        vp.setCoordinateReferenceSystem(crs);
	        
	      //Step 3: Add layers to map
	        //CoordinateReferenceSystem mapCRS = map.getCoordinateReferenceSystem();
	        map.addLayer(layer);	        
	        //Step 4: Save image
	        saveImage(map, "C:\\Download\\setl_out\\geometry.jpg", 800);
	        
	        
	        
		} catch (IOException | FactoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


        // import content from file system
        try (final FileInputStream fis = new FileInputStream(file)) {
            flowFile = session.importFrom(fis, flowFile);
        } catch (final IOException ioe) {
            getLogger().error("Could not fetch file {} from file system for {} due to {}; routing to failure", new Object[] {file, flowFile, ioe.toString()}, ioe);
            session.transfer(session.penalize(flowFile), REL_FAILURE);
            return;
        }

        session.getProvenanceReporter().fetch(flowFile, file.toURI().toString(), "Replaced content of FlowFile with contents of " + file.toURI(), stopWatch.getElapsed(TimeUnit.MILLISECONDS));
        session.transfer(flowFile, REL_SUCCESS);

        // It is critical that we commit the session before we perform the Completion Strategy. Otherwise, we could have a case where we
        // ingest the file, delete/move the file, and then NiFi is restarted before the session is committed. That would result in data loss.
        // As long as we commit the session right here, before we perform the Completion Strategy, we are safe.
        final FlowFile fetched = flowFile;
        session.commitAsync(() -> {
            performCompletionAction(completionStrategy, file, targetDirectoryName, fetched, context);
        });		
	}
    private void performCompletionAction(final String completionStrategy, final File file, final String targetDirectoryName, final FlowFile flowFile, final ProcessContext context) {
        // Attempt to perform the Completion Strategy action
        Exception completionFailureException = null;
        if (COMPLETION_DELETE.getValue().equalsIgnoreCase(completionStrategy)) {
            // convert to path and use Files.delete instead of file.delete so that if we fail, we know why
            try {
                delete(file);
            } catch (final IOException ioe) {
                completionFailureException = ioe;
            }
        } else if (COMPLETION_MOVE.getValue().equalsIgnoreCase(completionStrategy)) {
            final File targetDirectory = new File(targetDirectoryName);
            final File targetFile = new File(targetDirectory, file.getName());
            try {
                if (targetFile.exists()) {
                    final String conflictStrategy = context.getProperty(CONFLICT_STRATEGY).getValue();
                    if (CONFLICT_KEEP_INTACT.getValue().equalsIgnoreCase(conflictStrategy)) {
                        // don't move, just delete the original
                        Files.delete(file.toPath());
                    } else if (CONFLICT_RENAME.getValue().equalsIgnoreCase(conflictStrategy)) {
                        // rename to add a random UUID but keep the file extension if it has one.
                        final String simpleFilename = targetFile.getName();
                        final String newName;
                        if (simpleFilename.contains(".")) {
                            newName = StringUtils.substringBeforeLast(simpleFilename, ".") + "-" + UUID.randomUUID().toString() + "." + StringUtils.substringAfterLast(simpleFilename, ".");
                        } else {
                            newName = simpleFilename + "-" + UUID.randomUUID().toString();
                        }

                        move(file, new File(targetDirectory, newName), false);
                    } else if (CONFLICT_REPLACE.getValue().equalsIgnoreCase(conflictStrategy)) {
                        move(file, targetFile, true);
                    }
                } else {
                    move(file, targetFile, false);
                }
            } catch (final IOException ioe) {
                completionFailureException = ioe;
            }
        }

        // Handle completion failures
        if (completionFailureException != null) {
            getLogger().warn("Successfully fetched the content from {} for {} but failed to perform Completion Action due to {}; routing to success",
                new Object[] {file, flowFile, completionFailureException}, completionFailureException);
        }
    }
	protected void move(final File source, final File target, final boolean overwrite) throws IOException {
		final File targetDirectory = target.getParentFile();

		// convert to path and use Files.move instead of file.renameTo so that if we
		// fail, we know why
		final Path targetPath = target.toPath();
		if (!targetDirectory.exists()) {
			Files.createDirectories(targetDirectory.toPath());
		}

		final CopyOption[] copyOptions = overwrite ? new CopyOption[] { StandardCopyOption.REPLACE_EXISTING }
				: new CopyOption[] {};
		Files.move(source.toPath(), targetPath, copyOptions);
	}

	protected void delete(final File file) throws IOException {
		Files.delete(file.toPath());
	}

	protected boolean isReadable(final File file) {
		return file.canRead();
	}

	protected boolean isWritable(final File file) {
		return file.canWrite();
	}

	protected boolean isDirectory(final File file) {
		return file.isDirectory();
	}

	public void saveImage(final MapContent map, final String file, final int imageWidth) {

	    GTRenderer renderer = new StreamingRenderer();
	    renderer.setMapContent(map);

	    Rectangle imageBounds = null;
	    ReferencedEnvelope mapBounds = null;
	    try {
	        mapBounds = map.getMaxBounds();
	        double heightToWidth = mapBounds.getSpan(1) / mapBounds.getSpan(0);
	        imageBounds = new Rectangle(
	                0, 0, imageWidth, (int) Math.round(imageWidth * heightToWidth));

	    } catch (Exception e) {
	        // failed to access map layers
	        throw new RuntimeException(e);
	    }

	    BufferedImage image = new BufferedImage(imageBounds.width, imageBounds.height, BufferedImage.TYPE_INT_RGB);

	    Graphics2D gr = image.createGraphics();
	    gr.setPaint(Color.WHITE);
	    gr.fill(imageBounds);

	    try {
	        renderer.paint(gr, imageBounds, mapBounds);
	        File fileToSave = new File(file);
	        ImageIO.write(image, "jpeg", fileToSave);

	    } catch (IOException e) {
	        throw new RuntimeException(e);
	    }
	}
	
}
