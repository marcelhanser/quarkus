package io.quarkus.jacoco.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.MultiReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.csv.CSVFormatter;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.xml.XMLFormatter;
import org.jboss.logging.Logger;

public class ReportCreator implements Runnable {
    private static final Logger log = Logger.getLogger(ReportCreator.class);
    private final ReportInfo reportInfo;
    private final JacocoConfig config;

    public ReportCreator(ReportInfo reportInfo, JacocoConfig config) {
        this.reportInfo = reportInfo;
        this.config = config;
    }

    @Override
    public void run() {
        File targetdir = new File(reportInfo.reportDir);
        targetdir.mkdirs();
        try {
            //the jacoco data is also generated by a shutdown hook
            //we need to wait for the file. We wait at most 10s
            long abortTime = System.currentTimeMillis() + 10000;
            Path datafile = Paths.get(reportInfo.savedData);
            while (System.currentTimeMillis() < abortTime) {
                if (Files.exists(datafile)) {
                    break;
                }
                Thread.sleep(100);
            }
            //now it is created we wait for Jacoco to stop
            //this is awesomely hacky
            for (;;) {
                boolean running = false;
                for (Thread entry : Thread.getAllStackTraces().keySet()) {
                    if (entry.getClass().getName().startsWith("org.jacoco")) {
                        running = true;
                    }
                }
                if (!running) {
                    break;
                } else {
                    Thread.sleep(100);
                }
            }
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(datafile.toFile());
            final CoverageBuilder builder = new CoverageBuilder();
            final Analyzer analyzer = new Analyzer(
                    loader.getExecutionDataStore(), builder);
            for (String i : reportInfo.classFiles) {
                analyzer.analyzeAll(new File(i));
            }

            List<IReportVisitor> formatters = new ArrayList<>();
            addXmlFormatter(new File(targetdir, "jacoco.xml"), config.outputEncoding, formatters);
            addCsvFormatter(new File(targetdir, "jacoco.csv"), config.outputEncoding, formatters);
            addHtmlFormatter(targetdir, config.outputEncoding, config.footer.orElse(""), Locale.getDefault(),
                    formatters);

            //now for the hacky bit

            final IReportVisitor visitor = new MultiReportVisitor(formatters);
            visitor.visitInfo(loader.getSessionInfoStore().getInfos(),
                    loader.getExecutionDataStore().getContents());
            MultiSourceFileLocator sourceFileLocator = new MultiSourceFileLocator(4);
            for (String i : reportInfo.sourceDirectories) {
                sourceFileLocator.add(new DirectorySourceFileLocator(new File(i), config.sourceEncoding, 4));
            }
            final IBundleCoverage bundle = builder.getBundle(config.title.orElse(reportInfo.artifactId));
            visitor.visitBundle(bundle, sourceFileLocator);
            visitor.visitEnd();
            System.out.println("Generated Jacoco reports in " + targetdir);
            System.out.flush();
        } catch (Exception e) {
            System.err.println("Failed to generate Jacoco reports ");
            e.printStackTrace();
            System.err.flush();
        }
    }

    public void addXmlFormatter(final File targetfile, final String encoding, List<IReportVisitor> formatters)
            throws IOException {
        final XMLFormatter xml = new XMLFormatter();
        xml.setOutputEncoding(encoding);
        formatters.add(xml.createVisitor(new FileOutputStream(targetfile)));
    }

    public void addCsvFormatter(final File targetfile, final String encoding, List<IReportVisitor> formatters)
            throws IOException {
        final CSVFormatter csv = new CSVFormatter();
        csv.setOutputEncoding(encoding);
        formatters.add(csv.createVisitor(new FileOutputStream(targetfile)));
    }

    public void addHtmlFormatter(final File targetdir, final String encoding,
            final String footer, final Locale locale, List<IReportVisitor> formatters) throws IOException {
        final HTMLFormatter htmlFormatter = new HTMLFormatter();
        htmlFormatter.setOutputEncoding(encoding);
        htmlFormatter.setLocale(locale);
        if (footer != null) {
            htmlFormatter.setFooterText(footer);
        }
        formatters.add(htmlFormatter
                .createVisitor(new FileMultiReportOutput(targetdir)));
    }

}