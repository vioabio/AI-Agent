import { reviewStatusLabels } from "../../shared/displayLabels";

export const reviewStatusOptions = Object.entries(reviewStatusLabels).map(
  ([value, label]) => ({ value, label }),
);
