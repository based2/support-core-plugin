package com.cloudbees.jenkins.support.impl;

import com.cloudbees.jenkins.support.api.Component;
import com.cloudbees.jenkins.support.api.Container;
import com.cloudbees.jenkins.support.api.Content;
import com.cloudbees.jenkins.support.api.PrintedContent;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.MultiStageTimeSeries;
import hudson.model.TimeSeries;
import hudson.security.Permission;
import jenkins.model.Jenkins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * @since 
 */
@Extension
public class LoadStats extends Component {
    @NonNull
    @Override
    public Set<Permission> getRequiredPermissions() {
        return Collections.emptySet();
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Load Statistics";
    }

    @Override
    public void addContents(@NonNull Container container) {
        Jenkins jenkins = Jenkins.getInstance();
        add(container, "unlabeled", jenkins.unlabeledLoad);
        add(container, "overall", jenkins.overallLoad);
        for (Label l: jenkins.getLabels()) {
            try {
                add(container, String.format("label/%s", URLEncoder.encode(l.getName(), "UTF-8")), l.loadStatistics);
            } catch (UnsupportedEncodingException e) {
                // ignore UTF-8 is required by JLS specification
            }
        }
    }

    private void add(@NonNull Container container, String name, LoadStatistics stats) {
        boolean headless = Boolean.getBoolean("java.awt.headless");
        for (MultiStageTimeSeries.TimeScale scale: MultiStageTimeSeries.TimeScale.values()) {
            if (!headless) {
                BufferedImage image = stats.createTrendChart(scale).createChart().createBufferedImage(500, 400);
                container.add(new ImageContent(String.format("load-stats/%s/%s.png", name, scale.name().toLowerCase()),
                        image));
            }
            container.add(new CsvContent(String.format("load-stats/%s/%s.csv", name, scale.name().toLowerCase()), stats, scale));
        }
    }

    private static class ImageContent extends Content {
        private final BufferedImage image;
        public ImageContent(String name, BufferedImage image) {
            super(name);
            this.image = image;
        }

        @Override
        public void writeTo(OutputStream os) throws IOException {
            ImageIO.write(image, "png", os);
        }
    }

    private static final List<Field> FIELDS = findFields();

    private static List<Field> findFields() {
        List<Field> result = new ArrayList<Field>();
        for (Field f: LoadStatistics.class.getFields()) {
            if (Modifier.isPublic(f.getModifiers()) && MultiStageTimeSeries.class.isAssignableFrom(f.getType()) && f.getAnnotation(Deprecated.class) == null) {
                result.add(f);
            }
        }
        return result;
    }

    private class CsvContent extends PrintedContent {

        private final Map<String,float[]> data;
        private final long time;
        private final long clock;
        
        public CsvContent(String name, LoadStatistics stats,
                          MultiStageTimeSeries.TimeScale scale) {
            super(name);
            time = System.currentTimeMillis();
            clock = scale.tick;
            data = new TreeMap<String, float[]>();
            for (Field f: FIELDS) {
                try {
                    MultiStageTimeSeries ts = (MultiStageTimeSeries) f.get(stats);
                    if (ts != null) {
                        TimeSeries series = ts.pick(scale);
                        if (series != null) {
                            data.put(f.getName(), series.getHistory());
                        }
                    }
                } catch (IllegalAccessException e) {
                    continue;
                }
            }
        }

        @Override
        protected void printTo(PrintWriter out) throws IOException {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            
            out.print("time");
            int maxLen = 0;
            for (Map.Entry<String,float[]> entry: data.entrySet()) {
                out.print(',');
                out.print(entry.getKey());
                maxLen = Math.max(maxLen, entry.getValue().length);
            }
            out.println();
            for (int row = maxLen - 1; row >= 0; row--) {
                out.print(dateFormat.format(new Date(time - clock*(maxLen - row))));
                for (float[] h: data.values()) {
                    out.print(',');
                    if (h.length > row) {
                        out.print(h[row]);
                    }
                }
                out.println();
            }
        }
    }
}
