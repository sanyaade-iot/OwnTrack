<?php
/* Configuration goes here. Please change these settings */

    /* Paste your own common secret here. It should a random string of more then 20
    characters. The EXACT same string needs to be configured in the OwnTrack app. */
    $commonSecret = "test156";

    $hostServer = "localhost";
    $databaseUser = "owntrack";
    $databasePassword = "";
    $tableName = "owntrack";

/* End of Configuration */


    $input = json_decode(file_get_contents("php://input"), true);

    $salt = $input["a"]["a"];
    $hash = $input["a"]["b"];
    $ping = $input["a"]["c"] == 1 ? true : false;

    $rightHash = strtoupper(hash("sha256", $commonSecret.$salt));

    if ($hash === $rightHash) {
        if ($ping) {
            echo 130;
            exit(0);
        }

        $mysqli = new mysqli($hostServer, $databaseUser, $databasePassword, $tableName);

        if ($mysqli->connect_errno) {
            exit(0);
        }

        if (!($stmt = $mysqli->prepare("INSERT INTO `used_salt` (`salt`) VALUES (?)"))) {
            exit(0);
        }

        if (!$stmt->bind_param("s", $salt)) {
            exit(0);
        }

        if (!$stmt->execute()) {
            exit(0);
        }

        $stmt->close();

        if (!($stmt = $mysqli->prepare("INSERT INTO `location_history` (`timestamp`, `latitude`, `longitude`, `accuracy`, `speed`, `provider`) VALUES (FROM_UNIXTIME(?), ?, ?, ?, ?, ?);"))) {
            exit(0);
        }

        if (!$ping) {
            $locations = $input["b"];
            $insertedLines = 0;
            foreach ($locations as $location) {
                $insertedLines++;

                if (!$stmt->bind_param("idddds", floor($location["time"] / 1000), $location["lat"], $location["lon"], $location["accuracy"], $location["speed"], $location["provider"])) {
                    exit(0);
                }

                if (!$stmt->execute()) {
                    exit(0);
                }
            }

            $stmt->close();
            $mysqli->close();

            echo $insertedLines;
        }
    } else {
        echo 612;
    }
?>
