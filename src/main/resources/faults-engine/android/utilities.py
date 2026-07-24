import csv
import json
import os
from typing import Optional

import pandas as pd


def _parse_bool(value: object) -> bool:
    return str(value).strip().lower() in {"1", "true", "yes"}


def load_mappings(output_dir: str = "output"):
    """
    Load the Fault mapping (file_name, offset) and the File Sizes (File Name, file size) from the csv files
    """
    metadata_path = os.path.join(output_dir, "capture_metadata.json")
    page_size = 4096
    if os.path.exists(metadata_path):
        with open(metadata_path) as metadata_file:
            page_size = int(json.load(metadata_file).get("page_size", page_size))

    mapped_faults = []
    with open(os.path.join(output_dir, "mapped_faults.csv")) as csv_file:
        for row in csv.DictReader(csv_file):
            row["zip_entry_name"] = (
                row["zip_entry_name"] if row["zip_entry_name"] else None
            )
            row["offset"] = int(row["offset"])
            row["ts"] = int(row["ts"])
            row["is_major"] = _parse_bool(row["is_major"])
            row["page_size"] = page_size
            mapped_faults.append(row)

    file_sizes = []
    with open(os.path.join(output_dir, "file_sizes.csv")) as csv_file:
        for row in csv.DictReader(csv_file):
            row["size"] = int(row["size"])
            row["zip_entry_name"] = (
                row["zip_entry_name"] if row["zip_entry_name"] else None
            )
            row["file_offset"] = int(row["file_offset"])
            file_sizes.append(row)

    return mapped_faults, file_sizes


def extract_faults(
    file_name: str,
    zip_entry_name: Optional[str],
    file_sizes,
    mapped_faults,
    include_minor: bool = False,
):
    """
    Extract the faults mathcing the file name and optional zip entry name

    @returns a data frame with found faults, the size of the file, the offset of the zip entry within the file (applicably only if zip_entry_name is provided)
    """

    maybe_file = [
        file
        for file in file_sizes
        if file["file_name"] == file_name and file["zip_entry_name"] == zip_entry_name
    ]
    if not maybe_file:
        print(f"No file found: {file_name} - {zip_entry_name}")
        return pd.DataFrame([]), None, None

    file = maybe_file[0]

    file_size = file["size"]
    file_offset = file["file_offset"]

    faults = pd.DataFrame(
        [
            entry
            for entry in mapped_faults
            if entry["file_name"] == file_name
            and (not zip_entry_name or entry["zip_entry_name"] == zip_entry_name)
        ]
    )

    if faults.empty:
        return faults, file_size, file_offset

    if not include_minor:
        faults = faults[faults["is_major"]]

    if faults.empty:
        return faults, file_size, file_offset

    # Compute delta between fault offsets
    page_size = int(faults["page_size"].iloc[0])
    faults["offset"] = faults["offset"].div(page_size)
    faults["offset_diff"] = faults["offset"].diff()

    # Normalize timestamps
    faults["ts"] = faults["ts"] - faults["ts"].min()
    faults.reset_index(drop=True, inplace=True)
    return faults, file_size, file_offset
