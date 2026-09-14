export interface EdgeJoins {
  top: boolean;
  bottom: boolean;
  left: boolean;
  right: boolean;
}

export const NO_JOINS: EdgeJoins = {
  top: false,
  bottom: false,
  left: false,
  right: false,
};
