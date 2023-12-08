package training

import azure.downloadFileFromBlob
import azure.getBlobClientConnection
import azure.uploadFileToBlob
import config.Algorithm
import config.Config
import constants.MLFLOW_EXPERIMENT_NAME
import constants.PATH_TO_DATASET
import constants.PATH_TO_PREPROCESSED_DATASET
import constants.PATH_TO_PREPROCESSED_SMILE_Y_TEST_DATA
import constants.PATH_TO_PREPROCESSED_TEST_DATASET
import constants.PATH_TO_PREPROCESSED_TRAIN_DATASET
import constants.PATH_TO_PREPROCESSED_X_DATA
import constants.PATH_TO_PREPROCESSED_Y_DATA
import constants.PATH_TO_TRAINED_MODELS
import constants.PREPROCESSED_FILE_NAME
import constants.PREPROCESSED_SMILE_Y_TEST_DATASET_FILE_NAME
import constants.PREPROCESSED_TEST_DATASET_FILE_NAME
import constants.PREPROCESSED_TRAIN_DATASET_FILE_NAME
import constants.PREPROCESSED_X_DATA_FILE_NAME
import constants.PREPROCESSED_Y_DATA_FILE_NAME
import constants.PROCESSED_DATA_BLOB_CONTAINER_NAME
import constants.RAW_DATA_BLOB_CONTAINER_NAME
import constants.RAW_FILE_NAME
import constants.TRAINING_RESULT_DB_URL
import dataProcessing.dataPreProcessing
import dataProcessing.trainTestSplit
import dataProcessing.trainTestSplitForKotlinDL
import dataProcessing.trainTestSplitForSmile
import datatypeHandling.to2DDoubleArray
import datatypeHandling.toIntArray
import formulas.accuracy
import formulas.f1Score
import formulas.precision
import formulas.recall
import formulas.round
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import localFileManagement.deleteFolder
import localFileManagement.readCSVAsKotlinDF
import localFileManagement.readCSVAsKotlinDFAsync
import localFileManagement.readCSVAsSmileDFAsync
import localFileManagement.saveDLClassifierModel
import localFileManagement.storeKotlinDFAsCSVAsync
import mlflow.defineMLflowRunName
import mlflow.getMlflowClient
import mlflow.getOrCreateMlflowExperiment
import mlflow.logMlflowInformation
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.slf4j.LoggerFactory
import postgres.TrainingResults
import postgres.connectToDB
import postgres.createTable
import postgres.insertTrainingResults
import postgres.updateTableStructure
import java.io.File


abstract class TrainingPipeline(val cfg: Config) {
    abstract fun execute()
}


/**
 * Comprises all preprocessing steps and the training/prediction for an ensemble classifier.
 */
class EnsembleTrainingPipeline(cfg: Config, val algorithm: Algorithm) : TrainingPipeline(cfg) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun execute() = runBlocking {
        logger.info("Starting ensemble training pipeline...")
        val storageConnectionString = System.getenv("STORAGE_CONNECTION_STRING")

        if (!File(PATH_TO_DATASET).exists()) {
            logger.info("Downloading original dataset from Blob...")
            val blobClient = getBlobClientConnection(
                storageConnectionString = storageConnectionString,
                blobContainerName = RAW_DATA_BLOB_CONTAINER_NAME,
                fileName = RAW_FILE_NAME,
            )
            downloadFileFromBlob(blobClient = blobClient, filePath = PATH_TO_DATASET)
        }

        val data = readCSVAsKotlinDF(path = PATH_TO_DATASET)
        val (preProcessedDF, _, _) = dataPreProcessing(df = data)

        val (trainData, testData, _, yTestData) = trainTestSplitForSmile(
            data = preProcessedDF,
            testSize = cfg.preProcessing.testSize,
            randomState = cfg.preProcessing.seed,
        )

        // Store Kotlin DFs locally
        val dataframesAndPaths = listOf(
            preProcessedDF to PATH_TO_PREPROCESSED_DATASET,
            trainData to PATH_TO_PREPROCESSED_TRAIN_DATASET,
            testData to PATH_TO_PREPROCESSED_TEST_DATASET,
            yTestData.toDataFrame() to PATH_TO_PREPROCESSED_SMILE_Y_TEST_DATA
        )
        val preProcessedKotlinDFToStore = dataframesAndPaths.map { (df, path) ->
            async { storeKotlinDFAsCSVAsync(df, path) }
        }
        preProcessedKotlinDFToStore.awaitAll()

        // Upload preprocessed data to Blob
        val filesToUpload = listOf(
            Pair(PREPROCESSED_FILE_NAME, PATH_TO_PREPROCESSED_DATASET),
            Pair(PREPROCESSED_TRAIN_DATASET_FILE_NAME, PATH_TO_PREPROCESSED_TRAIN_DATASET),
            Pair(PREPROCESSED_TEST_DATASET_FILE_NAME, PATH_TO_PREPROCESSED_TEST_DATASET),
            Pair(PREPROCESSED_SMILE_Y_TEST_DATASET_FILE_NAME, PATH_TO_PREPROCESSED_SMILE_Y_TEST_DATA),
        )

        val deferredUploads = filesToUpload.map { (fileName, localFilePath) ->
            async {
                val blobClientPreProcessedData = getBlobClientConnection(
                    storageConnectionString = storageConnectionString,
                    blobContainerName = PROCESSED_DATA_BLOB_CONTAINER_NAME,
                    fileName = fileName
                )
                uploadFileToBlob(blobClient = blobClientPreProcessedData, filePath = localFilePath)
            }
        }
        deferredUploads.awaitAll()

        // Read in preprocessed data
        val preProcessedTrainData = async { readCSVAsSmileDFAsync(PATH_TO_PREPROCESSED_TRAIN_DATASET) }.await()
        val preProcessedTestData = async { readCSVAsSmileDFAsync(PATH_TO_PREPROCESSED_TEST_DATASET) }.await()
        val preProcessedYTestData = async { readCSVAsKotlinDFAsync(PATH_TO_PREPROCESSED_SMILE_Y_TEST_DATA) }.await()

        val model = when (algorithm) {
            Algorithm.DECISION_TREE -> DecisionTreeClassifier(decisionTreeConfig = cfg.train.decisionTree)
            Algorithm.RANDOM_FOREST -> RandomForestClassifier(randomForestConfig = cfg.train.randomForest)
            Algorithm.ADA_BOOST -> AdaBoostClassifier(adaBoostConfig = cfg.train.adaBoost)
            Algorithm.GRADIENT_BOOSTING -> GradientBoostingClassifier(gradientBoostingConfig = cfg.train.gradientBoosting)

            else -> throw IllegalArgumentException("Invalid algorithm for ensemble training pipeline")
        }
        logger.info("$algorithm training started")
        model.fit(trainDF = preProcessedTrainData)
        val predictions = model.predict(testDF = preProcessedTestData)

        val accuracy = accuracy(yTrue = preProcessedYTestData["diagnosis"].toIntArray(), yPred = predictions)
        logger.info("Accuracy: $accuracy")

        val precision = precision(yTrue = preProcessedYTestData["diagnosis"].toIntArray(), yPred = predictions)
        logger.info("Precision: $precision")

        val recall = recall(yTrue = preProcessedYTestData["diagnosis"].toIntArray(), yPred = predictions)
        logger.info("Recall: $recall")

        val f1Score = f1Score(yTrue = preProcessedYTestData["diagnosis"].toIntArray(), yPred = predictions)
        logger.info("F1-Score: $f1Score")

        val metrics = mapOf(
            "accuracy" to accuracy,
            "precision" to precision,
            "recall" to recall,
            "f1Score" to f1Score,
        )

        // Store training results in Postgres DB
        connectToDB(dbURL = TRAINING_RESULT_DB_URL)
        createTable(table = TrainingResults)
        updateTableStructure(table = TrainingResults)
        insertTrainingResults(algorithmName = algorithm.toString(), metrics = metrics)

        // Log training results in MLflow
        val (mlflowClient, isMlflowServerRunning) = getMlflowClient()
        val runID = getOrCreateMlflowExperiment(
            name = MLFLOW_EXPERIMENT_NAME,
            mlflowClient = mlflowClient,
            isMlflowServerRunning = isMlflowServerRunning,
        )

        logMlflowInformation(
            client = mlflowClient,
            runID = runID,
            metrics = metrics,
            paramKey = "algorithm",
            paramValue = algorithm.toString(),
            tagKey = "dataset",
            tagValue = "breast_cancer",
        )
        defineMLflowRunName(client = mlflowClient, runID = runID, algorithm = algorithm)
    }
}


class LogisticRegressionTrainingPipeline(cfg: Config, val algorithm: Algorithm) : TrainingPipeline(cfg) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun execute() = runBlocking {
        logger.info("Starting the Logistic Regression pipeline...")
        val storageConnectionString = System.getenv("STORAGE_CONNECTION_STRING")

        if (!File(PATH_TO_DATASET).exists()) {
            logger.info("Downloading original dataset from Blob...")
            val blobClient = getBlobClientConnection(
                storageConnectionString = storageConnectionString,
                blobContainerName = RAW_DATA_BLOB_CONTAINER_NAME,
                fileName = RAW_FILE_NAME,
            )
            downloadFileFromBlob(blobClient = blobClient, filePath = PATH_TO_DATASET)
        }

        val data = readCSVAsKotlinDF(path = PATH_TO_DATASET)
        val (preProcessedDF, xData, yData) = dataPreProcessing(df = data)

        // Store Kotlin DF's locally
        val kotlinDFsAndPaths = listOf(
            preProcessedDF to PATH_TO_PREPROCESSED_DATASET,
            xData to PATH_TO_PREPROCESSED_X_DATA,
            yData.toDataFrame() to PATH_TO_PREPROCESSED_Y_DATA,
        )
        val preProcessedKotlinDFsToStore = kotlinDFsAndPaths.map { (df, path) ->
            async { storeKotlinDFAsCSVAsync(df, path) }
        }
        preProcessedKotlinDFsToStore.awaitAll()

        // Upload preprocessed data to Blob
        val filesToUpload = listOf(
            Pair(PREPROCESSED_FILE_NAME, PATH_TO_PREPROCESSED_DATASET),
            Pair(PREPROCESSED_X_DATA_FILE_NAME, PATH_TO_PREPROCESSED_X_DATA),
            Pair(PREPROCESSED_Y_DATA_FILE_NAME, PATH_TO_PREPROCESSED_Y_DATA),
        )

        val deferredUploads = filesToUpload.map {
            async {
                val blobClientPreProcessedData = getBlobClientConnection(
                    storageConnectionString = storageConnectionString,
                    blobContainerName = PROCESSED_DATA_BLOB_CONTAINER_NAME,
                    fileName = it.first
                )
                uploadFileToBlob(blobClient = blobClientPreProcessedData, filePath = it.second)
            }
        }
        deferredUploads.awaitAll()

        val prePreProcessedXData = async { readCSVAsKotlinDF(path = PATH_TO_PREPROCESSED_X_DATA) }.await()
        val prePreProcessedYData = async { readCSVAsKotlinDF(path = PATH_TO_PREPROCESSED_Y_DATA) }.await()

        val (xTrain, xTest, yTrain, yTest) = trainTestSplit(
            xData = prePreProcessedXData,
            yData = prePreProcessedYData["diagnosis"],
            testSize = cfg.preProcessing.testSize,
            randomState = cfg.preProcessing.seed,
        )

        // Convert training dataframes of type Kotlin DataFrame to make them compatible with Smile
        val xTrainDoubleArray = xTrain.to2DDoubleArray()
        val xTestDoubleArray = xTest.to2DDoubleArray()

        // Convert test columns of type Kotlin DataColumn to make them compatible with Smile
        val yTrainIntArray = yTrain.toIntArray()
        val yTestIntArray = yTest.toIntArray()

        // Train the model
        val logisticRegression = LogisticRegression(logisticRegressionConfig = cfg.train.logisticRegression)
        logisticRegression.fit(xTrain = xTrainDoubleArray, yTrain = yTrainIntArray)
        // Calculate y-predictions based on the x-test set
        val predictions = logisticRegression.predict(xTest = xTestDoubleArray)
        logger.info("Logistic Regression training started")

        // Calculate accuracy of y-predictions compared to y-test set
        val accuracy = accuracy(yTrue = yTestIntArray, yPred = predictions)
        logger.info("Accuracy: $accuracy")

        val precision = precision(yTrue = yTestIntArray, yPred = predictions)
        logger.info("Precision: $precision")

        val recall = recall(yTrue = yTestIntArray, yPred = predictions)
        logger.info("Recall: $recall")

        val f1Score = f1Score(yTrue = yTestIntArray, yPred = predictions)
        logger.info("F1-Score: $f1Score")

        val metrics = mapOf(
            "accuracy" to accuracy,
            "precision" to precision,
            "recall" to recall,
            "f1Score" to f1Score,
        )

        // Store training results in Postgres DB
        connectToDB(dbURL = TRAINING_RESULT_DB_URL)
        createTable(table = TrainingResults)
        insertTrainingResults(algorithmName = algorithm.toString(), metrics = metrics)

        // Log training result in MLflow
        val (mlflowClient, isMlflowServerRunning) = getMlflowClient()
        val runID = getOrCreateMlflowExperiment(
            name = MLFLOW_EXPERIMENT_NAME,
            mlflowClient = mlflowClient,
            isMlflowServerRunning = isMlflowServerRunning,
        )
        logMlflowInformation(
            client = mlflowClient,
            runID = runID,
            metrics = metrics,
            paramKey = "algorithm",
            paramValue = algorithm.toString(),
            tagKey = "dataset",
            tagValue = "breast_cancer",
        )
        defineMLflowRunName(client = mlflowClient, runID = runID, algorithm = algorithm)
    }
}


/**
 * Comprises all preprocessing steps and the training/prediction for a Deep Learning Classifier.
 */
class DeepLearningTrainingPipeline(cfg: Config, val algorithm: Algorithm) : TrainingPipeline(cfg) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun execute(): Unit = runBlocking {
        logger.info("Starting the Deep Learning Classifier pipeline...")
        val storageConnectionString = System.getenv("STORAGE_CONNECTION_STRING")

        if (!File(PATH_TO_DATASET).exists()) {
            logger.info("Downloading original dataset from Blob...")
            val blobClient = getBlobClientConnection(
                storageConnectionString = storageConnectionString,
                blobContainerName = RAW_DATA_BLOB_CONTAINER_NAME,
                fileName = RAW_FILE_NAME,
            )
            downloadFileFromBlob(blobClient = blobClient, filePath = PATH_TO_DATASET)
        }

        val data = readCSVAsKotlinDF(path = PATH_TO_DATASET)
        val (_, xData, yData) = dataPreProcessing(df = data)

        // Store Kotlin DF locally
        val kotlinDFsAndPaths = listOf(
            xData to PATH_TO_PREPROCESSED_X_DATA,
            yData.toDataFrame() to PATH_TO_PREPROCESSED_Y_DATA,
        )
        val preProcessedKotlinDFsToStore = kotlinDFsAndPaths.map { (df, path) ->
            async { storeKotlinDFAsCSVAsync(df, path) }
        }
        preProcessedKotlinDFsToStore.awaitAll()

        // Upload preprocessed data to Blob
        val filesToUpload = listOf(
            Pair(PREPROCESSED_X_DATA_FILE_NAME, PATH_TO_PREPROCESSED_X_DATA),
            Pair(PREPROCESSED_Y_DATA_FILE_NAME, PATH_TO_PREPROCESSED_Y_DATA),
        )
        val deferredUploads = filesToUpload.map {
            async {
                val blobClientPreProcessedData = getBlobClientConnection(
                    storageConnectionString = storageConnectionString,
                    blobContainerName = PROCESSED_DATA_BLOB_CONTAINER_NAME,
                    fileName = it.first
                )
                uploadFileToBlob(blobClient = blobClientPreProcessedData, filePath = it.second)
            }
        }
        deferredUploads.awaitAll()

        val (train, test) = trainTestSplitForKotlinDL(
            xData = xData,
            yData = yData,
            trainSize = cfg.preProcessingDL.trainSize
        )

        val deepLearningClassifier = DeepLearningClassifier(deepLearningClassifierConfig = cfg.train.deepLearningClassifier)
        val (dlModel, accuracy) = deepLearningClassifier.fitAndPredict(trainData = train, testData = test)
        logger.info("Deep Learning training started")

        // Delete folder if older than > 2 days
        deleteFolder(folderPath = File(PATH_TO_TRAINED_MODELS))
        // Save the model results
        saveDLClassifierModel(model = dlModel)

        accuracy?.let { nonNullAccuracy ->
            logger.info("Accuracy: ${round(value = nonNullAccuracy, places = 4)}")
        }

        val metrics = mapOf(
            "accuracy" to round(value = accuracy, places = 4),
            "precision" to 0.0,
            "recall" to 0.0,
            "f1Score" to 0.0,
        )

        // Store training results in Postgres DB
        connectToDB(dbURL = TRAINING_RESULT_DB_URL)
        createTable(table = TrainingResults)
        updateTableStructure(table = TrainingResults)

        insertTrainingResults(
            algorithmName = algorithm.toString(),
            metrics = metrics,
        )

        // Log training result in MLflow
        val metricsForMlflow = mapOf(
            "accuracy" to round(value = accuracy, places = 4),
        )
        val (mlflowClient, isMlflowServerRunning) = getMlflowClient()
        val runID = getOrCreateMlflowExperiment(
            name = MLFLOW_EXPERIMENT_NAME,
            mlflowClient = mlflowClient,
            isMlflowServerRunning = isMlflowServerRunning,
        )
        logMlflowInformation(
            client = mlflowClient,
            runID = runID,
            metrics = metricsForMlflow,
            paramKey = "algorithm",
            paramValue = algorithm.toString(),
            tagKey = "dataset",
            tagValue = "breast_cancer",
        )
        defineMLflowRunName(client = mlflowClient, runID = runID, algorithm = algorithm)
    }
}
