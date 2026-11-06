import json
import threading
import unittest
import urllib.request

from rec_service import (
    CollaborativeFilteringService,
    ExperimentEvaluator,
    InteractionEvent,
    NeuralRankingTrainer,
    RecommendationBackendState,
    VectorSimilarityIndex,
    create_http_server,
)


class TestGeekifyRecommendationBackend(unittest.TestCase):
    def test_vector_similarity_index_l2_cosine_and_exclusions(self) -> None:
        idx = VectorSimilarityIndex(dim=4)
        idx.upsert("vid_malayalam_melody", [0.9, 0.8, 0.1, 0.0])
        idx.upsert("vid_malayalam_match", [0.88, 0.82, 0.09, 0.01])
        idx.upsert("vid_metal_heavy", [-0.8, -0.7, 0.9, 0.8])

        results = idx.query_nearest([0.9, 0.8, 0.1, 0.0], top_k=2, exclude_ids={"vid_malayalam_melody"})
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0][0], "vid_malayalam_match")
        self.assertGreater(results[0][1], 0.98)
        self.assertGreater(results[0][1], results[1][1])

    def test_collaborative_filtering_temporal_cutoff_and_cohort_privacy(self) -> None:
        cf = CollaborativeFilteringService(latent_dim=8, min_cohort_listeners=3)
        u1 = cf.anonymize_user_id("alice@example.com")
        u2 = cf.anonymize_user_id("bob@example.com")
        u3 = cf.anonymize_user_id("charlie@example.com")

        # Only 2 users -> below min_cohort_listeners=3 -> neutral fallback 0.50
        small_events = [
            InteractionEvent(u1, "track_a", "artist_1", "LIKED", 1000),
            InteractionEvent(u2, "track_a", "artist_1", "COMPLETED", 1100),
        ]
        stats_small = cf.fit(small_events)
        self.assertFalse(stats_small["meets_privacy_cohort"])
        self.assertAlmostEqual(cf.score_candidate(u1, ["track_a"], "track_b"), 0.50)

        # 3 users co-listening to track_a and track_b before cutoff=2000; track_future after cutoff=5000
        full_events = [
            InteractionEvent(u1, "track_a", "sushin shyam", "LIKED", 1000),
            InteractionEvent(u1, "track_b", "sushin shyam", "COMPLETED", 1200),
            InteractionEvent(u2, "track_a", "sushin shyam", "LIKED", 1300),
            InteractionEvent(u2, "track_b", "sushin shyam", "SAVED_TO_PLAYLIST", 1400),
            InteractionEvent(u3, "track_a", "sushin shyam", "COMPLETED", 1500),
            InteractionEvent(u3, "track_c", "anirudh", "SKIPPED_EARLY", 1600),
            # Future event after cutoff 2000 must not leak into model
            InteractionEvent(u2, "track_future", "future_artist", "LIKED", 5000),
        ]
        stats_full = cf.fit(full_events, cutoff_timestamp_ms=2000)
        self.assertTrue(stats_full["meets_privacy_cohort"])
        self.assertNotIn("track_future", cf.item_neighbors)

        score_b_for_u3 = cf.score_candidate(u3, ["track_a"], "track_b")
        score_skipped_for_u3 = cf.score_candidate(u3, ["track_a"], "track_c")
        self.assertGreater(score_b_for_u3, 0.55)
        self.assertLess(score_skipped_for_u3, 0.25)

    def test_neural_ranking_chronological_training_and_validation_gate(self) -> None:
        trainer = NeuralRankingTrainer()
        samples = []
        for i in range(40):
            ts = 1000 + i * 100
            if i % 2 == 0:
                feats = [0.90, 0.85, 0.80, 0.90, 0.85, 0.80, 0.75, 0.75, 0.70, 0.60, 0.70, 0.75, 0.90, 0.90, 0.05, 0.05]
                samples.append((feats, 1.0, ts))
            else:
                feats = [0.15, 0.10, 0.10, 0.15, 0.20, 0.15, 0.10, 0.10, 0.20, 0.20, 0.30, 0.20, 0.40, 0.15, 0.85, 0.80]
                samples.append((feats, 0.0, ts))

        res = trainer.train_chronological(samples, epochs=25, lr=0.03)
        self.assertTrue(res["promoted"])
        self.assertLessEqual(res["new_val_loss"], res["baseline_val_loss"] + 0.02)
        exported = trainer.export_weights_dict()
        self.assertEqual(len(exported["mlpW1"]), 24 * 16)
        self.assertEqual(len(exported["mlpW2"]), 24)

    def test_experiment_assignment_srm_and_z_test(self) -> None:
        g1 = ExperimentEvaluator.assign_group("user_12345")
        g2 = ExperimentEvaluator.assign_group("user_12345")
        self.assertEqual(g1, g2)

        _, srm_balanced = ExperimentEvaluator.check_srm(
            {"CONTROL": 250, "TREATMENT_A_CONTENT": 248, "TREATMENT_B_COLLAB": 252, "TREATMENT_C_NEURAL": 250}
        )
        self.assertFalse(srm_balanced)

        _, srm_skewed = ExperimentEvaluator.check_srm(
            {"CONTROL": 400, "TREATMENT_A_CONTENT": 200, "TREATMENT_B_COLLAB": 200, "TREATMENT_C_NEURAL": 200}
        )
        self.assertTrue(srm_skewed)

        z_res = ExperimentEvaluator.two_proportion_z_test(120, 500, 175, 500)
        self.assertTrue(z_res["significant"])
        self.assertGreater(z_res["lift"], 0.30)

    def test_http_json_api_endpoints(self) -> None:
        state = RecommendationBackendState()
        server = create_http_server("127.0.0.1", 0, state=state)
        port = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base_url = f"http://127.0.0.1:{port}"
            with urllib.request.urlopen(f"{base_url}/health") as resp:
                health = json.loads(resp.read().decode("utf-8"))
                self.assertEqual(health["status"], "ok")

            req = urllib.request.Request(
                f"{base_url}/vectors/upsert",
                data=json.dumps({"videoId": "song_1", "vector": [1.0] * 32}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req) as resp:
                up = json.loads(resp.read().decode("utf-8"))
                self.assertEqual(up["total"], 1)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
