package kr.co.mcmp.softwarecatalog.application.constants;

public enum ObjectStorageAccessMode {
    READ_ONLY,
    READ_WRITE;

    public boolean allowsUpload() {
        return this == READ_WRITE;
    }
}
