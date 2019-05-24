/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.sleuthkit.autopsy.newpackage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 *
 */
class FileSorter implements Comparator<ResultFile> {
    
    List<Comparator<ResultFile>> comparators = new ArrayList<>();
    
    FileSorter(SortingMethod method) {
        
        // Set up the comparators that should run on the files
        switch (method) {
            case BY_DATA_SOURCE:
                comparators.add(getDataSourceComparator());
                break;
            case BY_FILE_SIZE:
                comparators.add(getFileSizeComparator());
                break;
            case BY_FILE_TYPE:
                comparators.add(getFileTypeComparator());
                comparators.add(getMIMETypeComparator());
                break;
            case BY_FREQUENCY:
                comparators.add(getFrequencyComparator());
                break;
            case BY_KEYWORD_LIST_NAMES:
                break;
            case BY_PARENT_PATH:
                break;
            case BY_FILE_NAME:
                comparators.add(getFileNameComparator());
            default:
                break;
        }
        
        // Add the default comparator to the end. This will ensure a consistent sort
        // order regardless of the order the files were added to the list.
        comparators.add(getDefaultComparator());
    }
    
    @Override
    public int compare(ResultFile file1, ResultFile file2) {
        
        return 0;
    }
    
    private static Comparator<ResultFile> getDataSourceComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                // Sort large to small
                return Long.compare(file1.getAbstractFile().getDataSourceObjectId(), file2.getAbstractFile().getDataSourceObjectId());
            }
        };
    }    
    
    private static Comparator<ResultFile> getFileTypeComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                return Integer.compare(file1.getFileType().getRanking(), file2.getFileType().getRanking());
            }
        };
    }   
    
    private static Comparator<ResultFile> getParentPathComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                return compareStrings(file1.getAbstractFile().getParentPath(), file2.getAbstractFile().getParentPath());
            }
        };
    }   
    
    private static Comparator<ResultFile> getFrequencyComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                return Integer.compare(file1.getFrequency().getRanking(), file2.getFrequency().getRanking());
            }
        };
    }  
    
    private static Comparator<ResultFile> getMIMETypeComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                // Secondary sort on the MIME type
                return compareStrings(file1.getAbstractFile().getMIMEType(), file2.getAbstractFile().getMIMEType());
            }
        };
    }  
    
    private static Comparator<ResultFile> getFileSizeComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                // Sort large to small
                return -1 * Long.compare(file1.getAbstractFile().getSize(), file2.getAbstractFile().getSize());
            }
        };
    }
    
    private static Comparator<ResultFile> getFileNameComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                return compareStrings(file1.getAbstractFile().getName(), file2.getAbstractFile().getName());
            }
        };
    }
    
    /**
     * A final default comparison between two ResultFile objects.
     * Currently this is on file name and then object ID. It can be changed but
     * should always include something like the object ID to ensure a 
     * consistent sorting when the rest of the compared fields are the same.
     * 
     * @return 
     */
    private static Comparator<ResultFile> getDefaultComparator() {
        return new Comparator<ResultFile>() {
            @Override
            public int compare(ResultFile file1, ResultFile file2) {
                // For now, compare file names and then object ID (to ensure a consistent sort)
                int result = getFileNameComparator().compare(file1, file2);
                if (result == 0) {
                    return Long.compare(file1.getAbstractFile().getId(), file2.getAbstractFile().getId());
                }
                return result;
            }
        };
    }
    
    /**
     * Compare two strings alphabetically. Nulls are allowed.
     * 
     * @param s1
     * @param s2
     * 
     * @return 
     */
    private static int compareStrings(String s1, String s2) {
        if (s1 == null) {
            s1 = "";
        }
        if (s2 == null) {
            s2 = "";
        }
        return s1.compareTo(s2);
    }

    enum SortingMethod {
        BY_DATA_SOURCE,
        BY_FILE_NAME,
        BY_FILE_SIZE,
        BY_FILE_TYPE,
        BY_FREQUENCY,
        BY_KEYWORD_LIST_NAMES,
        BY_PARENT_PATH;
    }
}
