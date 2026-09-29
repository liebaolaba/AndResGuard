package com.tencent.mm.util;

import com.tencent.mm.androlib.res.decoder.ARSCDecoder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class FileOperation {
  private static final int BUFFER = 8192;

  public static boolean fileExists(String filePath) {
    if (filePath == null) {
      return false;
    }

    File file = new File(filePath);
    if (file.exists()) return true;
    return false;
  }

  public static boolean deleteFile(String filePath) {
    if (filePath == null) {
      return true;
    }

    File file = new File(filePath);
    if (file.exists()) {
      return file.delete();
    }
    return true;
  }

  public static long getlist(File f) {
    if (f == null || (!f.exists())) {
      return 0;
    }
    if (!f.isDirectory()) {
      return 1;
    }
    long size;
    File flist[] = f.listFiles();
    size = flist.length;
    for (int i = 0; i < flist.length; i++) {
      if (flist[i].isDirectory()) {
        size = size + getlist(flist[i]);
        size--;
      }
    }
    return size;
  }

  public static long getFileSizes(File f) {
    long size = 0;
    if (f.exists() && f.isFile()) {
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(f);
        size = fis.available();
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        try {
          if (fis != null) {
            fis.close();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return size;
  }

  public static boolean deleteDir(File file) {
    if (file == null || (!file.exists())) {
      return false;
    }
    if (file.isFile()) {
      file.delete();
    } else if (file.isDirectory()) {
      File files[] = file.listFiles();
      for (int i = 0; i < files.length; i++) {
        deleteDir(files[i]);
      }
    }
    file.delete();
    return true;
  }

  public static void copyFileUsingStream(File source, File dest) throws IOException {
    FileInputStream is = null;
    FileOutputStream os = null;
    File parent = dest.getParentFile();
    if (parent != null && (!parent.exists())) {
      parent.mkdirs();
    }
    try {
      is = new FileInputStream(source);
      os = new FileOutputStream(dest, false);

      byte[] buffer = new byte[BUFFER];
      int length;
      while ((length = is.read(buffer)) > 0) {
        os.write(buffer, 0, length);
      }
    } finally {
      if (is != null) {
        is.close();
      }
      if (os != null) {
        os.close();
      }
    }
  }

  public static boolean checkDirectory(String dir) throws IOException {
    File dirObj = new File(dir).getAbsoluteFile();
    String path = dirObj.getPath();

    // ★ 安全检查：不能是根目录
    File parent = dirObj.getParentFile();
    if (parent == null) {
      throw new IOException("refuse to delete root: " + path);
    }
    // ★ 安全检查：路径长度至少 3（避免 D:\ 这种）
    if (path.length() < 3) {
      throw new IOException("refuse to delete short path: " + path);
    }

    deleteDir(dirObj);
    if (!dirObj.exists()) {
      dirObj.mkdirs();
    }
    return true;
  }

  public static File checkFile(String dir) {
    deleteFile(dir);
    File file = new File(dir);
    try {
      file.createNewFile();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return file;
  }

  // 大小写冲突映射：原始路径 → 磁盘实际路径
  private static final Map<String, String> sCaseMapping = new HashMap<>();
  private static final Map<String, String> sReverseMapping = new HashMap<>();
  private static final Set<String> WINDOWS_RESERVED = new HashSet<>(Arrays.asList(
          // DOS 设备名（不带扩展名也不能用）
          "con", "prn", "aux", "nul",

          // 串口 1-9
          "com1", "com2", "com3", "com4", "com5",
          "com6", "com7", "com8", "com9",

          // 并口 1-9
          "lpt1", "lpt2", "lpt3", "lpt4", "lpt5",
          "lpt6", "lpt7", "lpt8", "lpt9"
  ));
  // 孤立文件：磁盘相对路径 → ZIP 原始路径
  private static final Map<String, String> sOrphanFiles = new HashMap<>();
//   "laba/xxx.xml" → "res/nul.xml"

  public static void addOrphanFile(String diskRelPath, String originalPath) {
    sOrphanFiles.put(diskRelPath, originalPath);
  }

  public static String getOrphanOriginalPath(String diskRelPath) {
    return sOrphanFiles.get(diskRelPath);
  }

  public static void clearOrphanFiles() {
    sOrphanFiles.clear();
  }
  /**
   * 查询磁盘上的实际路径（如果没有冲突，返回原始路径）
   */
  public static String getCaseMapping(String originalPath) {
    return sCaseMapping.get(originalPath);
  }

  public static String getOriginalPath(String actualPath) {
    // 精确查
    String exact = sReverseMapping.get(actualPath);
    if (exact != null) return exact;

    // 逐级向上查父目录
    int idx = actualPath.length();
    while ((idx = actualPath.lastIndexOf('/', idx - 1)) > 0) {
      String prefix = actualPath.substring(0, idx + 1);   // "res/a1z4/"
      String suffix = actualPath.substring(idx + 1);      // "file.txt"

      String originalPrefix = sReverseMapping.get(prefix);
      if (originalPrefix != null) {
        return originalPrefix + suffix;                 // "res/foo/file.txt"
      }
    }

    // 无匹配
    return actualPath;
  }

  public static void putCaseMapping(String original, String actual) {
    sCaseMapping.put(original, actual);
    sReverseMapping.put(actual, original);   // 反向
  }

  public static void clearCaseMapping() {
    sCaseMapping.clear();
    sReverseMapping.clear();
  }

  public static HashMap<String, Integer> unZipAPk(String fileName, String filePath) throws IOException {
    checkDirectory(filePath);
    clearCaseMapping();
    clearOrphanFiles();

    Set<String> lowerNames = new HashSet<>();
    Set<String> exactNames = new HashSet<>();
    ZipFile zipFile = new ZipFile(fileName);
    Enumeration emu = zipFile.entries();
    HashMap<String, Integer> compress = new HashMap<>();

    try {
      while (emu.hasMoreElements()) {
        ZipEntry entry = (ZipEntry) emu.nextElement();

        // 目录
        if (entry.isDirectory()) {
          String originalName = entry.getName();
          String actualName = originalName;

          if (!exactNames.add(originalName)) continue;

          if (needsRename(originalName, filePath, lowerNames, true)) {
            actualName = hashName(originalName, 0, true);
            int attempt = 1;
            while (lowerNames.contains(actualName.toLowerCase(Locale.ROOT))) {
              actualName = hashName(originalName, attempt, true);
              attempt++;
            }
            putCaseMapping(originalName, actualName);
//            System.err.printf("[rename-dir] %s → %s\n", originalName, actualName);
          }
          lowerNames.add(actualName.toLowerCase(Locale.ROOT));

          createFileSafe(filePath, actualName);
          continue;
        }

        String originalName = entry.getName();

        // 跳过同名
        if (!exactNames.add(originalName)) continue;

        // 需要改名？
        String actualName = originalName;
        if (needsRename(originalName, filePath, lowerNames, false)) {
          actualName = hashName(originalName, 0, false);
          int attempt = 1;
          while (lowerNames.contains(actualName.toLowerCase(Locale.ROOT))) {
            actualName = hashName(originalName, attempt, false);             // 传attempt
            attempt++;
          }
          putCaseMapping(originalName, actualName);
//          System.err.printf("[rename-fileed] %s → %s\n", originalName, actualName);
        }
        lowerNames.add(actualName.toLowerCase(Locale.ROOT));

        // 文件：用 createFileSafe（内含目录创建）
        File file = createFileSafe(filePath, actualName);

        try (BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(entry));
             FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER)) {
          byte[] buf = new byte[BUFFER];
          int len;
          while ((len = bis.read(buf, 0, BUFFER)) != -1) {
            bos.write(buf, 0, len);
          }
          bos.flush();
        }

        String compatibaleresult = originalName.replace("\\", "/");
        compress.put(compatibaleresult, entry.getMethod());
      }
    } finally {
      zipFile.close();
    }
    return compress;
  }

  /**
   * 判断条目是否需要改名
   */
  private static boolean needsRename(String name, String base, Set<String> lowerNames, boolean isDir) {
    // 路径超长
    String normalizedName = name.replace('/', File.separatorChar);
    String fullPath = base + File.separator + normalizedName;
    if (fullPath.length() > 200) {
      System.err.printf("[rename-reason=length] %d chars: %s\n", fullPath.length(), name);
      return true;
    }

    // 大小写冲突
    if (lowerNames.contains(name.toLowerCase(Locale.ROOT))) {
//      System.err.printf("[rename-reason=case] %s\n", name);
      return true;
    }

    // 取最后一级
    String lastSegment = name;
    // 目录：去掉末尾 /
    while (lastSegment.endsWith("/") || lastSegment.endsWith("\\")) {
      lastSegment = lastSegment.substring(0, lastSegment.length() - 1);
    }
    int lastSlash = lastSegment.lastIndexOf('/');
    if (lastSlash >= 0) {
      lastSegment = lastSegment.substring(lastSlash + 1);
    }

    // Windows 保留名（文件去扩展名，目录用整段）
    String checkName = isDir ? lastSegment
            : (lastSegment.contains(".")
            ? lastSegment.substring(0, lastSegment.indexOf("."))
            : lastSegment);
    if (WINDOWS_RESERVED.contains(checkName.toLowerCase(Locale.ROOT))) {
      System.err.printf("[rename-reason=reserved] %s\n", name);
      return true;
    }

    // 非法字符
    if (lastSegment.matches(".*[:*?\"<>|].*")) {
      System.err.printf("[rename-reason=illegal-char] %s\n", name);
      return true;
    }

    // 尾部空格/点
    if (lastSegment.endsWith(".") || lastSegment.endsWith(" ")) {
      System.err.printf("[rename-reason=trailing] %s\n", name);
      return true;
    }

    return false;
  }

  /**
   * 生成安全短名——同时适用于文件和目录
   * @param isDir 是否目录
   */
  private static String hashName(String originalName, int attempt, boolean isDir) {
    // 目录去末尾分隔符
    String name = originalName;
    boolean hasTrailingSlash = false;
    if (isDir) {
      while (name.endsWith("/") || name.endsWith("\\")) {
        name = name.substring(0, name.length() - 1);
        hasTrailingSlash = true;
      }
    }

    // 找最后一段
    int lastSlash = name.lastIndexOf('/');
    String result;

    if (lastSlash < 0) {
      // 顶级
      result = ARSCDecoder.hashToShort(name, attempt);
    } else {
      String dir = name.substring(0, lastSlash + 1);
      String lastSeg = name.substring(lastSlash + 1);

      if (isDir) {
        // 目录：hash 最后一段
        String hash = ARSCDecoder.hashToShort(lastSeg, attempt);
        result = dir + hash;
      } else {
        // 文件：hash 最后一段 + 保留扩展名
        String ext = ARSCDecoder.getFileExtension(lastSeg);
        String baseName = lastSeg.substring(0, lastSeg.length() - ext.length());
        String hash = ARSCDecoder.hashToShort(baseName, attempt);
        result = dir + hash + ext;
      }
    }

    return hasTrailingSlash ? result + "/" : result;
  }

  /**
   * 安全创建文件——处理 Windows 超长路径
   */
  private static File createFileSafe(String basePath, String relativePath) throws IOException {
    String normalizedPath = relativePath.replace('/', File.separatorChar);
    // 如果是目录（末尾有分隔符），去掉末尾
    boolean isDir = normalizedPath.endsWith(File.separator);
    if (isDir) {
      normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
    }

    String fullPath = basePath + File.separator + normalizedPath;

    File file;
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") && fullPath.length() > 200) {
      File abs = new File(fullPath).getAbsoluteFile();
      String p = abs.getPath();
      if (!p.startsWith("\\\\?\\")) p = "\\\\?\\" + p;
      file = new File(p);
    } else {
      file = new File(fullPath);
    }

    if (isDir) {
      // 目录：创建它自己
      if (!file.exists()) {
        if (!file.mkdirs()) {
          System.err.printf("[warn] mkdirs failed: %s\n", file.getAbsolutePath());
        }
      }
    } else {
      // 文件：创建父目录
      File parent = file.getParentFile();
      if (parent != null && !parent.exists()) {
        if (!parent.mkdirs()) {
          System.err.printf("[warn] mkdirs failed: %s\n", parent.getAbsolutePath());
        }
      }
    }

    return file;
  }


  @SuppressWarnings("rawtypes")
//  public static HashMap<String, Integer> unZipAPk(String fileName, String filePath) throws IOException {
//    checkDirectory(filePath);
//    ZipFile zipFile = new ZipFile(fileName);
//    Enumeration emu = zipFile.entries();
//    HashMap<String, Integer> compress = new HashMap<>();
//    try {
//      while (emu.hasMoreElements()) {
//        ZipEntry entry = (ZipEntry) emu.nextElement();
//        if (entry.isDirectory()) {
//          new File(filePath, entry.getName()).mkdirs();
//          continue;
//        }
//        BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(entry));
//
//        File file = new File(filePath + File.separator + entry.getName());
//
//        File parent = file.getParentFile();
//        if (parent != null && (!parent.exists())) {
//          parent.mkdirs();
//        }
//        //要用linux的斜杠
//        String compatibaleresult = entry.getName();
//        if (compatibaleresult.contains("\\")) {
//          compatibaleresult = compatibaleresult.replace("\\", "/");
//        }
//        compress.put(compatibaleresult, entry.getMethod());
//        FileOutputStream fos = new FileOutputStream(file);
//        BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER);
//
//        byte[] buf = new byte[BUFFER];
//        int len;
//        while ((len = bis.read(buf, 0, BUFFER)) != -1) {
//          fos.write(buf, 0, len);
//        }
//        bos.flush();
//        bos.close();
//        bis.close();
//      }
//    } finally {
//      zipFile.close();
//    }
//    return compress;
//  }

  /**
   * zip list of file
   *
   * @param resFileList file(dir) list
   * @param baseFolder file(dir) base folder, we should calc relative path of resFile with base
   * @param zipFile output zip file
   * @param compressData compress data
   * @throws IOException io exception
   */
  public static void zipFiles(
      Collection<File> resFileList, File baseFolder, File zipFile, HashMap<String, Integer> compressData)
      throws IOException {
    ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), BUFFER));
    for (File resFile : resFileList) {
      if (resFile.exists()) {
        if (resFile.getAbsolutePath().contains(baseFolder.getAbsolutePath())) {
          String relativePath = baseFolder.toURI().relativize(resFile.getParentFile().toURI()).getPath();
          // remove slash at end of relativePath
          if (relativePath.length() > 1) {
            relativePath = relativePath.substring(0, relativePath.length() - 1);
          } else {
            relativePath = "";
          }
          zipFile(resFile, zipOut, relativePath, compressData);
        } else {
          zipFile(resFile, zipOut, "", compressData);
        }
      }
    }
    zipOut.close();
  }

  private static void zipFile(
      File resFile, ZipOutputStream zipout, String rootpath, HashMap<String, Integer> compressData) throws IOException {
    rootpath = rootpath + (rootpath.trim().length() == 0 ? "" : File.separator) + resFile.getName();
    if (resFile.isDirectory()) {
      File[] fileList = resFile.listFiles();
      for (File file : fileList) {
        zipFile(file, zipout, rootpath, compressData);
      }
    } else {
      final byte[] fileContents = readContents(resFile);
      //这里需要强转成linux格式，果然坑！！
      if (rootpath.contains("\\")) {
        rootpath = rootpath.replace("\\", "/");
      }

      // 查表：是否是孤立文件
      String orphanOriginal = FileOperation.getOrphanOriginalPath(rootpath);
      String zipEntryName = (orphanOriginal != null) ? orphanOriginal : rootpath;
      //  "laba/xxx.xml"        → "res/nul.xml"     （孤立文件）
      //  "laba/a/r7f080001.png" → 自身              （arsc 里的资源）

      // 压缩方式
      int compressMethod;
      if (compressData.containsKey(zipEntryName)) {
        compressMethod = compressData.get(zipEntryName);
      } else if (compressData.containsKey(rootpath)) {
        compressMethod = compressData.get(rootpath);
      } else {
        String lower = zipEntryName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".9.png") || lower.endsWith(".so") || lower.endsWith(".arsc")) {
          compressMethod = ZipEntry.STORED;
        } else {
          compressMethod = ZipEntry.DEFLATED;
        }
      }

      ZipEntry entry = new ZipEntry(zipEntryName);   // 用原始名

////      int compressMethod;
//      if (!compressData.containsKey(rootpath)) {
//        System.err.printf(String.format("do not have the compress data path = %s in resource.asrc\n", rootpath));
//        //throw new IOException(String.format("do not have the compress data path=%s", rootpath));
//        return;
////        compressMethod = ZipEntry.STORED;
////      }else{
////        compressMethod = compressData.get(rootpath);
////      }
//      }
//      int compressMethod = compressData.get(rootpath);
//      ZipEntry entry = new ZipEntry(rootpath);

      if (compressMethod == ZipEntry.DEFLATED) {
        entry.setMethod(ZipEntry.DEFLATED);
      } else {
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(fileContents.length);
        final CRC32 checksumCalculator = new CRC32();
        checksumCalculator.update(fileContents);
        entry.setCrc(checksumCalculator.getValue());
      }
      zipout.putNextEntry(entry);
      zipout.write(fileContents);
      zipout.flush();
      zipout.closeEntry();
    }
  }

  private static byte[] readContents(final File file) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final int bufferSize = 4096;
    try {
      final FileInputStream in = new FileInputStream(file);
      final BufferedInputStream bIn = new BufferedInputStream(in);
      int length;
      byte[] buffer = new byte[bufferSize];
      byte[] bufferCopy;
      while ((length = bIn.read(buffer, 0, bufferSize)) != -1) {
        output.write(buffer, 0, length);
      }
      bIn.close();
    } finally {
      output.close();
    }
    return output.toByteArray();
  }
}
