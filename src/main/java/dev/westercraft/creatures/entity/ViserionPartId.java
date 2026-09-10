package dev.westercraft.creatures.entity;

// One entry per hitbox/seat slot on ViserionEntity. Each slot is a fixed offset (radius,
// angleDeg, yOffset) from the entity's own current position/yaw — no per-bone animation
// tracking (an earlier version live-sampled GeckoLib bone world transforms client-side and
// relayed them to the server over the network; simpler and more robust to just use a fixed
// offset instead, at the cost of not hugging wing-flap/neck-turn animation exactly). SEAT has
// no corresponding ViserionPart entity — it's used only by updatePassengerPosition.
//
// HEAD/NECK/TORSO/LOWER_BODY/LEFT_WING/RIGHT_WING values are derived from the real bb model
// (viserion.geo.json): each named bone's REST-POSE pivot, converted to blocks, split into
// forward/lateral/vertical, then folded into radius/angleDeg. TAIL_MID/TAIL_TIP/LEFT_LEG/
// RIGHT_LEG are eyeballed instead (that part of the model's bone hierarchy has per-bone
// rotations several levels deep, and hand-computing world position from a raw child pivot
// without walking the whole parent chain is exactly the trap that produced a wrong seat
// estimate earlier in this project) — good enough directionally (tail continuing backward/down
// at 180°, legs at ±90° near the ground). SEAT's yOffset is empirically tuned (14.36 too low/
// in-body, 20.0 too high — 17.0 split the difference), not derived from the model.
//
// All radius/yOffset values are in blocks at the entity's current render scale — ViserionRenderer
// applies 0.4x. Values here are pre-multiplied by that 0.4 so slotPosition() doesn't need to
// know the render scale at all; angleDeg is a direction, not a distance, so it's untouched by
// any of this.
public enum ViserionPartId {
	HEAD("head", 8.0f, 0f, 6.3f),
	// Fire-breath origin only (no hitbox entity, like SEAT) - deliberately separate from HEAD so
	// moving this doesn't drag the head hitbox out into empty space in front of the model. Real
	// bone data this time, not eyeballed: "bone49", the mouth/teeth/tongue bone group nested two
	// levels under Head (Head -> bone41 -> bone49) in viserion.geo.json, pivot [-7.3, 393,
	// -726.8]. Bedrock-format bone pivots are absolute model-space, not parent-relative (verified
	// by cross-checking Head's own pivot against its already-validated radius/yOffset), so the
	// same raw-units-to-blocks conversion applies directly: blocks = raw * (0.4 render scale /
	// 16 units-per-block). That puts the mouth much further forward than HEAD's own hitbox
	// center (18.17 vs 8.0) - a big jump, but bone49's z (-726.8) really is that much further
	// forward than Head's own pivot z (-218) in the raw model, and "still coming from the neck"
	// after HEAD was already pushed to 8.0 is consistent with needing to go further still.
	MOUTH("mouth", 18.17f, 0f, 9.83f),
	// Weak-point hitboxes near the head, ASOIAF-canon dragon-slaying lore (Aegon's shield-arm
	// wound not included, but "put an arrow/spear in the eye" shows up repeatedly) — small
	// targets right at the sides of the skull, slightly forward and lower than HEAD's own
	// center. Eyeballed off HEAD's numbers (small lateral ± angle, slightly less radius/yOffset
	// to sit toward the front-lower part of the skull rather than dead center) rather than the
	// model's real "righteye" bone pivot, same reasoning as TAIL_MID/etc. above.
	LEFT_EYE("left_eye", 5.2f, 12f, 6.6f),
	RIGHT_EYE("right_eye", 5.2f, -12f, 6.6f),
	NECK("neck", 3.93f, 0f, 6.18f),
	TORSO("torso", 2.4f, 0f, 5.38f),
	LOWER_BODY("lower_body", 2.91f, 180f, 3.56f),
	TAIL_MID("tail_mid", 3.27f, 180f, 2.62f),
	TAIL_TIP("tail_tip", 3.64f, 180f, 1.74f),
	LEFT_WING("left_wing", 6.26f, 35f, 4.94f),
	RIGHT_WING("right_wing", 6.26f, -35f, 4.94f),
	LEFT_LEG("left_leg", 2.91f, 90f, 2.18f),
	RIGHT_LEG("right_leg", 2.91f, -90f, 2.18f),
	// "On top of the back, between the wings" — matches where the rider actually stood in F3
	// ground-truth testing against the model, back when that testing was done against the old
	// 0.55x-scaled-down model (no longer applicable at the current scale, see class comment).
	SEAT("seat", 5.09f, 0f, 6.8f);

	public final String label;
	public final float defaultRadius;
	public final float defaultAngleDeg;
	public final float defaultYOffset;

	ViserionPartId(String label, float defaultRadius, float defaultAngleDeg, float defaultYOffset) {
		this.label = label;
		this.defaultRadius = defaultRadius;
		this.defaultAngleDeg = defaultAngleDeg;
		this.defaultYOffset = defaultYOffset;
	}
}
