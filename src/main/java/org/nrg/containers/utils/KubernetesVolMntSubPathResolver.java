package org.nrg.containers.utils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;


/**
 * Resolves Kubernetes volume subPaths from container paths for individual project
 * mounts.
 * 
 * <p>
 * Discovers actual storage mount configurations from /proc/mounts and calculates
 * correct subPath values for individual project mounts, supporting complex
 * storage across multiple servers/shares.
 * </p>
 * 
 * <p>
 * Usage:
 * 
 * <pre>
 * resolver.init("/nas");
 * String subPath = resolver.get("/data/xnat/archive/proj_1/arc001/...");
 * // Returns: "pool01/proj_1/xnat/arc001/..."
 * // based on /proc/mounts entry:
 * // nas-server:/nas/pool01/proj_1/xnat /data/xnat/archive/proj_1 ... 
 * </pre>
 * </p>
 */
@Slf4j
public enum KubernetesVolMntSubPathResolver {

    INSTANCE;

    static final String procMountsFile = "/proc/mounts";
    static final String pathSep = "/";

    /** Discovered mount mappings: xnat container path → storage subPath */
    private List<Mount> mounts = new ArrayList<>();

    /** Cached mount path to avoid re-reading /proc/mounts */
    private String cachedMountPath = null;

    /**
     * Mount mapping: xnat container mount point to storage subPath.
     */
    private class Mount {
        private String subPath;      // e.g., "pool01/proj_1/xnat"
        private String mountPoint;   // e.g., "/data/xnat/archive/proj_1"

        public Mount(String subPath, String mountPoint) {
            this.subPath = subPath;
            this.mountPoint = mountPoint;
        }

        public String toString() {
            return "(subPath=" + subPath + ", mountPoint=" + mountPoint + ")";
        }
    }

    /**
     * Strip leading and trailing path separators from a path string.
     * 
     * @param path The path to strip
     * @return Path without leading/trailing slashes
     */
    private String stripPathSeps(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith(pathSep)) {
            path = path.substring(1);
        }
        if (path.endsWith(pathSep)) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Initialize by reading /proc/mounts and discovering storage structure.
     * 
     * <p>
     * Parses /proc/mounts and matches share paths containing the specified
     * mountPath prefix. The mountPath is used to identify and strip the storage
     * prefix from share paths to calculate subPath values.
     * </p>
     * 
     * @param mountPath Storage prefix to match and strip (e.g."nas").
     *                  This determines which mounts are processed and what prefix
     *                  is removed from share paths.
     * @throws Exception if OS is Windows or /proc/mounts unreadable
     */
    public void init(String mountPath) throws Exception {

        log.debug("Current mounts.size(): {}", mounts.size());

        // Normalize: remove leading/trailing slashes
        mountPath = stripPathSeps(mountPath);

        // Check cache
        if (cachedMountPath == null) {
            // First time initialization, check OS compatibility
            if (System.getProperty("os.name").toUpperCase().startsWith("WINDOWS")) {
                throw new Exception("unsupported OS");
            }
        } else if (mountPath.equals(cachedMountPath)) {
            // No change in mountPath, cache is valid, skip re-initialization
            return;
        } else {
            // mountPath changed, clear cache and re-initialize
            mounts.clear();
        }

        cachedMountPath = mountPath;

        // Build regex pattern that matches share paths and extracts subPath
        // Example: if mountPath="nas", matches "nas-server:/nas/pool01/..."
        // and captures "/pool01/..." in group 'pth'
        Pattern patternSharePth = Pattern.compile(
            "^([^:]*:|//[^/]*)/" + Pattern.quote(mountPath) + "(?<pth>.*)$"
        );

        // Read /proc/mounts
        List<String> allLines = Files.readAllLines(Paths.get(procMountsFile));

        // Parse mount entries
        for (String line : allLines) {
            String[] mount = line.split(" ", 3);
            if (mount.length < 2) {
                continue;
            }

            // Try matching share path (mount[0])
            Matcher mSharePth = patternSharePth.matcher(mount[0]);
            if (!mSharePth.find()) {
                continue;
            }

            // Extract subPath (everything after /mountPath)
            String pth = mSharePth.group("pth");
            String subPath = stripPathSeps(pth);

            // Add to mounts list
            // subPath: extracted from mount[0] (e.g., "/pool01/proj_1/xnat")
            // mountPoint: from mount[1] (e.g., "/data/xnat/archive/proj_1")
            mounts.add(new Mount(subPath, mount[1]));
        }
    }

    /**
     * Get Kubernetes subPath for an xnat container path.
     * 
     * <p>
     * Example:
     * 
     * <pre>
     * Input:  /data/xnat/archive/proj_1/arc001/SAMPLE_01/SCANS/1/DICOM
     * Output: pool01/proj_1/xnat/arc001/SAMPLE_01/SCANS/1/DICOM
     * </pre>
     * </p>
     * 
     * @param containerPath Full path in xnat container (e.g., /data/xnat/archive/proj_1/...)
     * @return Kubernetes volumeMount subPath, or null if no match found
     */
    public String get(String containerPath) {

        // Find matching mount
        for (int i = 0; i < mounts.size(); i++) {
            Mount mount = mounts.get(i);

            if (containerPath.startsWith(mount.mountPoint)) {
                // Extract relative path and combine with subPath
                String relativePath = containerPath.substring(mount.mountPoint.length());
                String result = mount.subPath + relativePath;

                return result;
            }
        }

        return null;
    }
}