/*
 * Copyright 2013 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.errai.ui.rebind;

import static org.jboss.errai.ui.rebind.chain.TranslateCommand.Constants.VALUES;

import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.jboss.errai.codegen.InnerClass;
import org.jboss.errai.codegen.builder.ClassStructureBuilder;
import org.jboss.errai.codegen.builder.ConstructorBlockBuilder;
import org.jboss.errai.codegen.builder.impl.ClassBuilder;
import org.jboss.errai.codegen.exception.GenerationException;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.impl.build.BuildMetaClass;
import org.jboss.errai.codegen.util.Implementations;
import org.jboss.errai.codegen.util.Stmt;
import org.jboss.errai.common.metadata.RebindUtils;
import org.jboss.errai.config.rebind.AbstractAsyncGenerator;
import org.jboss.errai.config.rebind.GenerateAsync;
import org.jboss.errai.config.util.ClassScanner;
import org.jboss.errai.ioc.rebind.ioc.injector.InjectUtil;
import org.jboss.errai.reflections.Configuration;
import org.jboss.errai.reflections.Reflections;
import org.jboss.errai.reflections.scanners.ResourcesScanner;
import org.jboss.errai.reflections.util.ClasspathHelper;
import org.jboss.errai.reflections.util.ConfigurationBuilder;
import org.jboss.errai.reflections.util.FilterBuilder;
import org.jboss.errai.ui.client.local.spi.TranslationService;
import org.jboss.errai.ui.rebind.chain.TemplateChain;
import org.jboss.errai.ui.shared.MessageBundle;
import org.jboss.errai.ui.shared.api.annotations.Bundle;
import org.jboss.errai.ui.shared.api.annotations.Templated;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.ClientBundle.Source;
import com.google.gwt.resources.client.TextResource;

/**
 * Generates a concrete subclass of {@link TranslationService}. This class is
 * responsible for scanning the classpath for all bundles, and then making them
 * available during template translation.
 *
 * @author eric.wittmann@redhat.com
 */
@GenerateAsync(TranslationService.class)
public class TranslationServiceGenerator extends AbstractAsyncGenerator {

  private static final String GENERATED_CLASS_NAME = "TranslationServiceImpl";
  private static Pattern LOCALE_IN_FILENAME_PATTERN = Pattern.compile("([^_]*)_(\\w\\w)?(_\\w\\w)?\\.json");

  @Override
  public String generate(TreeLogger logger, GeneratorContext context, String typeName)
          throws UnableToCompleteException {
    return startAsyncGeneratorsAndWaitFor(TranslationService.class, context, logger,
            TranslationService.class.getPackage().getName(), GENERATED_CLASS_NAME);
  }

  @Override
  public String generate(TreeLogger logger, GeneratorContext context) {
    // The class we will be building is GeneratedTranslationService
    final ClassStructureBuilder<?> classBuilder = Implementations.extend(
            TranslationService.class, GENERATED_CLASS_NAME);
    ConstructorBlockBuilder<?> ctor = classBuilder.publicConstructor();

    // The bundles we've already done - do avoid dupes
    Set<String> processedBundles = new HashSet<String>();
    // The i18n keys found (per locale) while processing the bundles.
    Map<String, Set<String>> discoveredI18nMap = new HashMap<String, Set<String>>();

    // Scan for all @Bundle annotations.
    final Collection<MetaClass> bundleAnnotatedClasses = ClassScanner.getTypesAnnotatedWith(Bundle.class);
    // For each one, generate the code to load the translation and put that generated
    // code in the c'tor of the generated class (GeneratedTranslationService)
    for (MetaClass bundleAnnotatedClass : bundleAnnotatedClasses) {
      String bundlePath = getMessageBundlePath(bundleAnnotatedClass);

      // Skip if we've already processed this bundle.
      if (processedBundles.contains(bundlePath)) {
        continue;
      }

      // Now get all files in the message bundle (all localized versions)
      // TODO optimize this - scan the classpath once and then pull out just the resources we need
      MessageBundleScanner scanner = new MessageBundleScanner(
              new ConfigurationBuilder()
              .filterInputsBy(new FilterBuilder().include(".*json"))
              .setUrls(ClasspathHelper.forClassLoader())
              .setScanners(new MessageBundleResourceScanner(bundlePath)));
      Collection<String> resources = scanner.getStore().get(MessageBundleResourceScanner.class).values();
      // If we didn't find at least the specified root bundle file, that's a problem.
      if (!resources.contains(bundlePath)) {
        throw new GenerationException("Missing i18n bundle (specified in @Bundle): " + bundlePath);
      }

      // Now generate code to load up each of the JSON files and register them
      // with the translation service.
      for (String resource : resources) {
        // Generate this component's ClientBundle resource interface
        BuildMetaClass messageBundleResourceInterface = generateMessageBundleResourceInterface(resource);
        // Add it as an inner class to the generated translation service
        classBuilder.getClassDefinition().addInnerClass(new InnerClass(messageBundleResourceInterface));

        // Instantiate the ClientBundle MessageBundle resource
        final String msgBundleVarName = InjectUtil.getUniqueVarName();
        ctor.append(Stmt.declareVariable(messageBundleResourceInterface).named(msgBundleVarName)
                .initializeWith(Stmt.invokeStatic(GWT.class, "create", messageBundleResourceInterface)));

        // Create a dictionary from the message bundle and register it.
        String locale = getLocaleFromBundlePath(resource);
        ctor.append(Stmt.loadVariable("this").invoke("registerBundle",
                Stmt.loadVariable(msgBundleVarName).invoke("getContents").invoke("getText"),
                locale));

        recordBundleKeys(discoveredI18nMap, locale, resource);
      }

      processedBundles.add(bundlePath);
    }
    ctor.finish();

    generateI18nHelperFilesInto(discoveredI18nMap, RebindUtils.getErraiCacheDir());

    return classBuilder.toJavaString();
  }

  /**
   * Records all of the i18n keys found in the given bundle.
   * @param discoveredI18nMap
   * @param locale
   * @param bundlePath
   */
  protected static void recordBundleKeys(Map<String, Set<String>> discoveredI18nMap, String locale, String bundlePath) {
    InputStream is = null;
    try {
      Set<String> keys = discoveredI18nMap.get(locale);
      if (keys == null) {
        keys = new HashSet<String>();
        discoveredI18nMap.put(locale, keys);
      }
      is = TranslationServiceGenerator.class.getClassLoader().getResourceAsStream(bundlePath);
      JsonFactory jsonFactory = new JsonFactory();
      JsonParser jp = jsonFactory.createJsonParser(is);
      JsonToken token = jp.nextToken();
      while (token != null) {
        token = jp.nextToken();
        if (token == JsonToken.FIELD_NAME) {
          String name = jp.getCurrentName();
          keys.add(name);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      IOUtils.closeQuietly(is);
    }
  }

  /**
   * Gets the bundle name from the @Bundle annotation.
   * @param bundleAnnotatedClass
   */
  private String getMessageBundlePath(MetaClass bundleAnnotatedClass) {
    Bundle annotation = bundleAnnotatedClass.getAnnotation(Bundle.class);
    String name = annotation.value();
    if (name == null) {
      throw new GenerationException("@Bundle: bundle name must not be null].");
    }
    // Absolute path vs. relative path.
    if (name.startsWith("/")) {
      return name.substring(1);
    } else {
      String packageName = bundleAnnotatedClass.getPackageName();
      return packageName.replace('.', '/') + "/" + name;
    }
  }

  /**
   * Gets the name of the {@link MessageBundle} class.
   * @param bundlePath
   */
  private String getMessageBundleTypeName(final String bundlePath) {
    String typeName = bundlePath.replace(".json", "MessageBundleResource").replace('/', '.').replace('-', '_').replace('.', '_');
    return typeName;
  }

  /**
   * Create an inner interface for the given {@link MessageBundle} class and its
   * corresponding JSON resource.
   * @param bundlePath path to the message bundle
   */
  private BuildMetaClass generateMessageBundleResourceInterface(final String bundlePath) {
    final ClassStructureBuilder<?> componentMessageBundleResource = ClassBuilder
            .define(getMessageBundleTypeName(bundlePath)).publicScope()
            .interfaceDefinition().implementsInterface(MessageBundle.class)
            .implementsInterface(ClientBundle.class).body()
            .publicMethod(TextResource.class, "getContents")
            .annotatedWith(new Source() {
              @Override
              public Class<? extends Annotation> annotationType() {
                return Source.class;
              }

              @Override
              public String[] value() {
                return new String[]{bundlePath};
              }
            }).finish();
    return componentMessageBundleResource.getClassDefinition();
  }

  /**
   * Gets the locale information from the given bundle path.  For example,
   * if the bundle path is "org/example/myBundle_en_US.json" then this method will
   * return "en_US".
   * @param bundlePath path to the message bundle
   */
  public static String getLocaleFromBundlePath(String bundlePath) {
    Matcher matcher = LOCALE_IN_FILENAME_PATTERN.matcher(bundlePath);
    if (matcher != null && matcher.matches()) {
      StringBuilder locale = new StringBuilder();
      String lang = matcher.group(2);
      if (lang != null)
        locale.append(lang);
      String region = matcher.group(3);
      if (region != null)
        locale.append("_").append(region.substring(1));
      return locale.toString();
    } else {
      return null;
    }
  }

  /**
   * Generates all helper files that developers can use to assist with i18n
   * work.  This includes the "missing i18n keys" report(s) as well as a set of
   * JSON files that can be used as a starting-point for translations.
   * @param discoveredI18nMap
   * @param destDir
   */
  protected static void generateI18nHelperFilesInto(Map<String, Set<String>> discoveredI18nMap, File destDir) {
    final Collection<MetaClass> templatedAnnotatedClasses = ClassScanner.getTypesAnnotatedWith(Templated.class);
    Map<String, String> allI18nValues = new HashMap<String, String>();
    Map<String, Map<String, String>> indexedI18nValues = new HashMap<String, Map<String, String>>();
    for (MetaClass templatedAnnotatedClass : templatedAnnotatedClasses) {
      String templateFileName = TemplatedCodeDecorator.getTemplateFileName(templatedAnnotatedClass);
      String templateBundleName = templateFileName.replaceAll(".html", ".json").replace('/', '_');

      final TemplateChain chain = TemplateChain.getInstance();
      chain.visitTemplate(templateFileName, templatedAnnotatedClass);

      Map<String, String> i18nValues = chain.getResult(templateFileName, VALUES);

      allI18nValues.putAll(i18nValues);
      Map<String, String> templateI18nValues = indexedI18nValues.get(templateBundleName);
      if (templateI18nValues == null) {
        indexedI18nValues.put(templateBundleName, i18nValues);
      } else {
        templateI18nValues.putAll(i18nValues);
      }
    }

    // Output a JSON file containing *all* of the keys that need translation.
    File allI18nValuesFile = new File(destDir, "errai-bundle-all.json");
    if (allI18nValuesFile.isFile())
      allI18nValuesFile.delete();
    outputBundleFile(allI18nValues, allI18nValuesFile, null);

    // Only bother with the missing/extra files if we discovered *something*
    // while processing.  If zero bundles were found, then they aren't currently
    // using i18n in any way.
    if (!discoveredI18nMap.isEmpty()) {
      // Output a JSON file containing only the keys that were found in existing JSON
      // bundle files but that are *not* needed (not found in a template).
      Set<String> discoveredDefaultI18nKeys = discoveredI18nMap.get(null);
      if (discoveredDefaultI18nKeys == null)
        discoveredDefaultI18nKeys = Collections.emptySet();
      Set<String> extraI18nKeys = new HashSet<String>(discoveredDefaultI18nKeys);
      extraI18nKeys.removeAll(allI18nValues.keySet());
      Map<String, String> m = new HashMap<String, String>();
      for (String extraKey : extraI18nKeys)
        m.put(extraKey, "");
      File extraI18nValuesFile = new File(destDir, "errai-bundle-extra.json");
      if (extraI18nValuesFile.isFile())
        extraI18nValuesFile.delete();
      outputBundleFile(m, extraI18nValuesFile, extraI18nKeys);

      // Ouput a JSON file containing just the i18n keys that are missing from the
      // existing i18n bundles (found in a template but missing from the bundles).
      Set<String> missingI18nKeys = new HashSet<String>(allI18nValues.keySet());
      missingI18nKeys.removeAll(discoveredDefaultI18nKeys);
      File missingI18nValuesFile = new File(destDir, "errai-bundle-missing.json");
      if (missingI18nValuesFile.isFile())
        missingI18nValuesFile.delete();
      outputBundleFile(allI18nValues, missingI18nValuesFile, missingI18nKeys);

      // TODO output -missing bundle files for each locale
    }
  }

  /**
   * Writes out a bundle (JSON) file to the given location.
   * @param i18nValues
   * @param bundleFile
   * @param onlyTheseKeys
   */
  private static void outputBundleFile(Map<String, String> i18nValues, File bundleFile, Set<String> onlyTheseKeys) {
    if (onlyTheseKeys != null && onlyTheseKeys.isEmpty())
      return;

    try {
      JsonFactory f = new JsonFactory();
      JsonGenerator g = f.createJsonGenerator(bundleFile, JsonEncoding.UTF8);
      g.useDefaultPrettyPrinter();
      g.writeStartObject();
      Set<String> orderedKeys = new TreeSet<String>(i18nValues.keySet());
      for (String key : orderedKeys) {
        String value = i18nValues.get(key);
        if (onlyTheseKeys == null || onlyTheseKeys.contains(key))
          g.writeStringField(key, value);
      }
      g.writeEndObject();
      g.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * A scanner that finds i18n message bundles.
   * @author eric.wittmann@redhat.com
   */
  private static class MessageBundleResourceScanner extends ResourcesScanner {
    private final String bundlePrefix;
    private final String bundleSuffix = ".json";
    /**
     * Constructor.
     * @param bundlePath
     */
    public MessageBundleResourceScanner(String bundlePath) {
      this.bundlePrefix = bundlePath.substring(0, bundlePath.lastIndexOf(".json"));
    }

    /**
     * @see org.jboss.errai.reflections.scanners.ResourcesScanner#acceptsInput(java.lang.String)
     */
    @Override
    public boolean acceptsInput(String file) {
      return file != null && file.startsWith(this.bundlePrefix) && file.endsWith(this.bundleSuffix);
    }
  }

  /**
   * Scanner used to find i18n message bundles on the classpath.
   *
   * @author eric.wittmann@redhat.com
   */
  private static class MessageBundleScanner extends Reflections {

    /**
     * Constructor.
     * @param config
     */
    public MessageBundleScanner(Configuration config) {
      super(config);
      scan();
    }

  }

}
