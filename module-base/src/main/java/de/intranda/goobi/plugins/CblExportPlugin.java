package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;
import org.goobi.beans.User;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.dms.ExportDms;
import de.sub.goobi.helper.FilesystemHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Fileformat;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class CblExportPlugin extends ExportDms implements IExportPlugin, IPlugin {
    @Getter
    private PluginType type = PluginType.Export;
    @Getter
    private String title = "plugin-intranda-export-cbl";

    private static final Namespace mets = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace xlink = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    private final Map<String, String> mimeTypeReplacementMap = Map.of("model\\/(.*)", "object/$1", "application\\/octet-stream", "object/gltf");
    private final Map<String, String> fileSuffixReplacementMap = Map.of("(.*)\\.bin", "$1.gltf");

    public CblExportPlugin() {
        super();
    }

    @Override
    public void imageDownload(Process myProzess, Path benutzerHome, String atsPpnBand, final String ordnerEndung)
            throws IOException, InterruptedException, SwapException, DAOException {

        /*
         * -------------------------------- dann den Ausgangspfad ermitteln --------------------------------
         */
        Path tifOrdner = Paths.get(myProzess.getImagesTifDirectory(false));

        /*
         * -------------------------------- jetzt die Ausgangsordner in die Zielordner kopieren --------------------------------
         */
        Path zielTif = Paths.get(benutzerHome.toString(), atsPpnBand + ordnerEndung);
        if (StorageProvider.getInstance().isFileExists(tifOrdner) && !StorageProvider.getInstance().list(tifOrdner.toString()).isEmpty()) {

            /* bei Agora-Import einfach den Ordner anlegen */
            if (myProzess.getProjekt().isUseDmsImport()) {
                if (!StorageProvider.getInstance().isFileExists(zielTif)) {
                    StorageProvider.getInstance().createDirectories(zielTif);
                }
            } else {
                /*
                 * wenn kein Agora-Import, dann den Ordner mit Benutzerberechtigung neu anlegen
                 */
                User myBenutzer = Helper.getCurrentUser();
                try {
                    if (myBenutzer == null) {
                        StorageProvider.getInstance().createDirectories(zielTif);
                    } else {
                        FilesystemHelper.createDirectoryForUser(zielTif.toString(), myBenutzer.getLogin());
                    }
                } catch (Exception e) {
                    Helper.setFehlerMeldung("Export canceled, error", "could not create destination directory");
                    log.error("could not create destination directory", e);
                }
            }

            /* jetzt den eigentlichen Kopiervorgang */
            DirectoryStream.Filter<Path> dataOrBinFilter = path -> {
                return StorageProvider.dataFilterString(path.toString()) || path.toString().matches("(?i).*\\.bin");
            };
            List<Path> files = StorageProvider.getInstance().listFiles(myProzess.getImagesTifDirectory(false), dataOrBinFilter);
            for (Path file : files) {
                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
                StorageProvider.getInstance().copyFile(file, target);
                //for 3d object files look for "helper files" with the same base name and copy them as well
                if (NIOFileUtils.objectNameFilter.accept(file)) {
                    copy3DObjectHelperFiles(myProzess, zielTif, file);
                }
            }
        }

        if (ConfigurationHelper.getInstance().isExportFilesFromOptionalMetsFileGroups()) {

            List<ProjectFileGroup> myFilegroups = myProzess.getProjekt().getFilegroups();
            if (myFilegroups != null && myFilegroups.size() > 0) {
                for (ProjectFileGroup pfg : myFilegroups) {
                    // check if source files exists
                    if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                        Path folder = Paths.get(myProzess.getMethodFromName(pfg.getFolder()));
                        if (folder != null && StorageProvider.getInstance().isFileExists(folder)
                                && !StorageProvider.getInstance().list(folder.toString()).isEmpty()) {
                            List<Path> files = StorageProvider.getInstance().listFiles(folder.toString());
                            for (Path file : files) {
                                Path target = Paths.get(zielTif.toString(), file.getFileName().toString());
                                StorageProvider.getInstance().copyFile(file, target);
                            }
                        }
                    }
                }
            }
        }
    }

    public void copy3DObjectHelperFiles(Process myProzess, Path zielTif, Path file)
            throws IOException, InterruptedException, SwapException, DAOException {
        Path tiffDirectory = Paths.get(myProzess.getImagesTifDirectory(true));
        String baseName = FilenameUtils.getBaseName(file.getFileName().toString());
        List<Path> helperFiles = StorageProvider.getInstance()
                .listDirNames(tiffDirectory.toString())
                .stream()
                .filter(dirName -> dirName.equals(baseName))
                .map(tiffDirectory::resolve)
                .collect(Collectors.toList());
        for (Path helperFile : helperFiles) {
            Path helperTarget = Paths.get(zielTif.toString(), helperFile.getFileName().toString());
            if (StorageProvider.getInstance().isDirectory(helperFile)) {
                StorageProvider.getInstance().copyDirectory(helperFile, helperTarget);
            } else {
                StorageProvider.getInstance().copyFile(helperFile, helperTarget);
            }
        }
    }

    @Override
    protected boolean writeMetsFile(Process myProzess, String targetFileName, Fileformat gdzfile, boolean writeLocalFilegroup)
            throws PreferencesException, WriteException, IOException, InterruptedException, SwapException, DAOException,
            TypeNotAllowedForParentException {

        boolean ret = writeMetsFile(myProzess, targetFileName, gdzfile, writeLocalFilegroup, false);

        try {
            corretMimeTypes(targetFileName);
        } catch (JDOMException | IOException e) {
            log.error("Error correctinr mimetypes", e);
        }

        return ret;
    }

    private void corretMimeTypes(String targetFileName) throws JDOMException, IOException {
        SAXBuilder builder = XmlTools.getSAXBuilder();
        Document doc = builder.build(targetFileName);
        XPathFactory xpath = XPathFactory.instance();
        XPathExpression<Element> expr = xpath.compile("//mets:file", Filters.element(), Collections.emptyMap(), mets);
        List<Element> fileElements = expr.evaluate(doc);
        for (Element fileElement : fileElements) {
            replaceMimetype(fileElement);
            replaceLocationHref(fileElement);
        }
        XMLOutputter xmlOutputter = new XMLOutputter();
        try (FileOutputStream fileOutputStream =
                new FileOutputStream(targetFileName)) {
            xmlOutputter.output(doc, fileOutputStream);
        }
    }

    protected void replaceLocationHref(Element fileElement) {
        Element locationElement = fileElement.getChild("FLocat", mets);
        if (locationElement != null) {
            String href = locationElement.getAttributeValue("href", xlink);
            if (StringUtils.isNotBlank(href)) {
                fileSuffixReplacementMap.entrySet().stream().filter(entry -> href.matches(entry.getKey())).findAny().ifPresent(entry -> {
                    String replacement = href.replaceAll(entry.getKey(), entry.getValue());
                    locationElement.setAttribute("href", replacement, xlink);
                });
            }
        }
    }

    protected void replaceMimetype(Element fileElement) {
        String mimetype = fileElement.getAttributeValue("MIMETYPE");
        if (StringUtils.isNotBlank(mimetype)) {
            mimeTypeReplacementMap.entrySet().stream().filter(entry -> mimetype.matches(entry.getKey())).findAny().ifPresent(entry -> {
                String replacement = mimetype.replaceAll(entry.getKey(), entry.getValue());
                fileElement.setAttribute("MIMETYPE", replacement);
            });
        }
    }

    public static void main(String[] args) {
        String mimetype = "model/gltf+json";
        String rep = mimetype.replaceAll("model\\/(.*)", "object/$1");
        System.out.println(rep);
    }

}
